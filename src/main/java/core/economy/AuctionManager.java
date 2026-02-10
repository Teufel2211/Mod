package core.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import core.util.Safe;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import core.config.ConfigManager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuctionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(ItemStack.class, new ItemStackAdapter())
        .setPrettyPrinting()
        .create();

    private static final File AUCTIONS_FILE = new File("auctions.json");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static final Map<Integer, Auction> auctions = new ConcurrentHashMap<>();
    // UUID (string) -> list of items awaiting /auction claim
    private static final Map<String, List<ItemStack>> pendingDeliveries = new ConcurrentHashMap<>();
    private static int nextAuctionId = 1;
    private static volatile MinecraftServer serverRef;

    public enum CancelResult { NOT_FOUND, NOT_ALLOWED, HAS_BIDS, CANCELED }
    public enum BuyNowResult { NOT_FOUND, NOT_ALLOWED, NO_BUYOUT, INSUFFICIENT_FUNDS, SUCCESS, ERROR }

    private static final class AuctionData {
        Map<Integer, Auction> auctions = new HashMap<>();
        Map<String, List<ItemStack>> deliveries = new HashMap<>();
        int nextId = 1;
    }

    public static class Auction {
        public int id;
        public UUID sellerId;
        public ItemStack item;
        public BigDecimal startingBid;
        public BigDecimal currentBid;
        public UUID currentBidder;
        public BigDecimal buyoutPrice;
        public long endTime;
        public boolean active;

        public Auction(int id, UUID sellerId, ItemStack item, BigDecimal startingBid, BigDecimal buyoutPrice, long duration) {
            this.id = id;
            this.sellerId = sellerId;
            this.item = item;
            this.startingBid = startingBid;
            this.currentBid = startingBid;
            this.buyoutPrice = buyoutPrice;
            this.endTime = System.currentTimeMillis() + duration;
            this.active = true;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("AuctionManager.onServerStarted", () -> {
            serverRef = server;
            loadAuctions(server);
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("AuctionManager.saveAuctions", () -> saveAuctions(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> Safe.run("AuctionManager.processAuctions", () -> processAuctions(server)));
    }

    private static void processAuctions(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();

        for (Auction auction : auctions.values()) {
            if (auction.active && currentTime >= auction.endTime) {
                endAuction(auction);
            }
        }
    }

    public static int createAuction(UUID sellerId, ItemStack item, BigDecimal startingBid) {
        return createAuction(sellerId, item, startingBid, null);
    }

    public static int createAuction(UUID sellerId, ItemStack item, BigDecimal startingBid, BigDecimal buyoutPrice) {
        if (buyoutPrice != null && buyoutPrice.compareTo(startingBid) < 0) return -1;

        double fee = startingBid.doubleValue() * ConfigManager.getConfig().economy.auctionFeePercent;
        BigDecimal totalFee = startingBid.add(BigDecimal.valueOf(fee));

        if (EconomyManager.getBalance(sellerId).compareTo(totalFee) < 0) return -1;

        // Charge the listing fee
        EconomyManager.chargePlayer(sellerId, BigDecimal.valueOf(fee), EconomyManager.TransactionType.AUCTION_FEE, "Auction listing fee");

        int auctionId = nextAuctionId++;
        Auction auction = new Auction(auctionId, sellerId, item, startingBid, buyoutPrice, 24 * 60 * 60 * 1000L); // 24 hours
        auctions.put(auctionId, auction);

        saveAuctions(null);
        return auctionId;
    }

    public static int createAuctionFromPlayer(ServerPlayerEntity seller, BigDecimal startingBid) {
        return createAuctionFromPlayer(seller, startingBid, null);
    }

    public static int createAuctionFromPlayer(ServerPlayerEntity seller, BigDecimal startingBid, BigDecimal buyoutPrice) {
        if (seller == null) return -1;

        ItemStack inHand = seller.getMainHandStack();
        if (inHand == null || inHand.isEmpty()) return -1;

        ItemStack toSell = inHand.copy();
        // Remove the stack from the seller immediately so the item can't be duplicated.
        seller.setStackInHand(seller.getActiveHand(), ItemStack.EMPTY);

        int id = createAuction(seller.getUuid(), toSell, startingBid, buyoutPrice);
        if (id < 0) {
            // Restore item if auction creation failed.
            seller.setStackInHand(seller.getActiveHand(), toSell);
        }
        return id;
    }

    public static boolean bidOnAuction(UUID bidderId, int auctionId, BigDecimal bidAmount) {
        Auction auction = auctions.get(auctionId);
        if (auction == null || !auction.active) return false;

        if (bidAmount.compareTo(auction.currentBid) <= 0) return false;

        if (EconomyManager.getBalance(bidderId).compareTo(bidAmount) < 0) return false;

        // Refund previous bidder
        if (auction.currentBidder != null) {
            EconomyManager.rewardPlayer(auction.currentBidder, auction.currentBid,
                EconomyManager.TransactionType.AUCTION_FEE, "Auction bid refund");
        }

        // Charge new bidder
        EconomyManager.chargePlayer(bidderId, bidAmount, EconomyManager.TransactionType.AUCTION_FEE, "Auction bid");

        auction.currentBid = bidAmount;
        auction.currentBidder = bidderId;

        saveAuctions(null);
        return true;
    }

    public static CancelResult cancelAuction(ServerPlayerEntity requester, int auctionId, boolean allowAdmin) {
        Auction auction = auctions.get(auctionId);
        if (auction == null || !auction.active) return CancelResult.NOT_FOUND;
        if (!auction.sellerId.equals(requester.getUuid()) && !allowAdmin) return CancelResult.NOT_ALLOWED;
        if (auction.currentBidder != null) return CancelResult.HAS_BIDS;

        auctions.remove(auctionId);
        addDelivery(auction.sellerId, auction.item);
        saveAuctions(null);
        return CancelResult.CANCELED;
    }

    public static BuyNowResult buyNow(ServerPlayerEntity buyer, int auctionId) {
        try {
            if (buyer == null) return BuyNowResult.ERROR;
            Auction auction = auctions.get(auctionId);
            if (auction == null || !auction.active) return BuyNowResult.NOT_FOUND;
            if (auction.buyoutPrice == null) return BuyNowResult.NO_BUYOUT;
            if (auction.sellerId != null && auction.sellerId.equals(buyer.getUuid())) return BuyNowResult.NOT_ALLOWED;

            BigDecimal price = auction.buyoutPrice;
            if (EconomyManager.getBalance(buyer.getUuid()).compareTo(price) < 0) return BuyNowResult.INSUFFICIENT_FUNDS;

            // Charge buyer first to avoid unlocking the current bid if the buyout can't be paid.
            boolean charged = EconomyManager.chargePlayer(buyer.getUuid(), price, EconomyManager.TransactionType.AUCTION_FEE, "Auction buyout");
            if (!charged) return BuyNowResult.INSUFFICIENT_FUNDS;

            // Refund current bidder (their bid amount was held in escrow).
            if (auction.currentBidder != null) {
                EconomyManager.rewardPlayer(auction.currentBidder, auction.currentBid,
                    EconomyManager.TransactionType.AUCTION_FEE, "Auction bid refund");
            }

            // Pay seller
            EconomyManager.rewardPlayer(auction.sellerId, price,
                EconomyManager.TransactionType.AUCTION_FEE, "Auction buyout sale");

            // Deliver item
            ItemStack stack = auction.item.copy();
            boolean inserted = buyer.getInventory().insertStack(stack);
            if (!inserted && !stack.isEmpty()) {
                buyer.dropItem(stack, false);
            }

            auction.active = false;
            auctions.remove(auctionId);
            saveAuctions(null);
            return BuyNowResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Buyout failed for auction {}", auctionId, e);
            return BuyNowResult.ERROR;
        }
    }

    public static int claimDeliveries(ServerPlayerEntity player) {
        if (player == null) return 0;
        String key = player.getUuid().toString();
        List<ItemStack> items = pendingDeliveries.remove(key);
        if (items == null || items.isEmpty()) return 0;

        int delivered = 0;
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;
            boolean inserted = player.getInventory().insertStack(stack);
            if (!inserted && !stack.isEmpty()) {
                player.dropItem(stack, false);
            }
            delivered++;
        }

        saveAuctions(null);
        return delivered;
    }

    private static void endAuction(Auction auction) {
        auction.active = false;

        if (auction.currentBidder != null) {
            // Pay seller
            EconomyManager.rewardPlayer(auction.sellerId, auction.currentBid,
                EconomyManager.TransactionType.AUCTION_FEE, "Auction sale");

            // Deliver item to winner. If they are offline/full inventory, queue for /auction claim.
            ServerPlayerEntity winner = serverRef == null ? null : serverRef.getPlayerManager().getPlayer(auction.currentBidder);
            if (winner != null) {
                ItemStack stack = auction.item.copy();
                boolean inserted = winner.getInventory().insertStack(stack);
                if (!inserted && !stack.isEmpty()) {
                    winner.dropItem(stack, false);
                }
            } else {
                addDelivery(auction.currentBidder, auction.item);
            }
        } else {
            // No bids, return item to seller (or queue for /auction claim)
            ServerPlayerEntity seller = serverRef == null ? null : serverRef.getPlayerManager().getPlayer(auction.sellerId);
            if (seller != null) {
                ItemStack stack = auction.item.copy();
                boolean inserted = seller.getInventory().insertStack(stack);
                if (!inserted && !stack.isEmpty()) {
                    seller.dropItem(stack, false);
                }
            } else {
                addDelivery(auction.sellerId, auction.item);
            }
        }

        auctions.remove(auction.id);
        saveAuctions(null);
    }

    public static void loadAuctions(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                if (!AUCTIONS_FILE.exists()) return;

                try (FileReader reader = new FileReader(AUCTIONS_FILE)) {
                    AuctionData data = GSON.fromJson(reader, AuctionData.class);
                    if (data == null) return;

                    auctions.clear();
                    if (data.auctions != null) auctions.putAll(data.auctions);

                    pendingDeliveries.clear();
                    if (data.deliveries != null) pendingDeliveries.putAll(data.deliveries);

                    nextAuctionId = data.nextId > 0 ? data.nextId : 1;

                    LOGGER.info("Loaded {} auctions and {} pending deliveries", auctions.size(), pendingDeliveries.size());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load auctions", e);
            }
        });
    }

    public static void saveAuctions(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                AuctionData data = new AuctionData();
                data.auctions = new HashMap<>(auctions);
                data.deliveries = new HashMap<>(pendingDeliveries);
                data.nextId = nextAuctionId;
                try (FileWriter writer = new FileWriter(AUCTIONS_FILE)) {
                    GSON.toJson(data, writer);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to save auctions", e);
            }
        });
    }

    public static Map<Integer, Auction> getActiveAuctions() {
        Map<Integer, Auction> active = new HashMap<>();
        for (Map.Entry<Integer, Auction> entry : auctions.entrySet()) {
            if (entry.getValue().active) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }

    private static void addDelivery(UUID playerId, ItemStack item) {
        if (playerId == null || item == null || item.isEmpty()) return;
        pendingDeliveries.computeIfAbsent(playerId.toString(), k -> Collections.synchronizedList(new ArrayList<>()))
            .add(item.copy());
    }

    // Local adapter to avoid class init coupling with EconomyManager
    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(Registries.ITEM.getId(src.getItem()) + ":" + src.getCount());
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String raw = json.getAsString();
            int lastColon = raw.lastIndexOf(':');
            if (lastColon <= 0 || lastColon >= raw.length() - 1) return ItemStack.EMPTY;

            try {
                Identifier id = Identifier.of(raw.substring(0, lastColon));
                int count = Integer.parseInt(raw.substring(lastColon + 1));
                return new ItemStack(Registries.ITEM.get(id), count);
            } catch (Exception e) {
                LOGGER.warn("Failed to deserialize ItemStack: {}", raw);
                return ItemStack.EMPTY;
            }
        }
    }
}
