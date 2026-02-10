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
import net.minecraft.item.Item;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import core.util.Safe;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import core.config.ConfigManager;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;

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

public class ShopManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(ItemStack.class, new ItemStackAdapter())
        .setPrettyPrinting()
        .create();

    private static final File SHOP_FILE = new File("shop.json");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static final Map<String, ShopItem> shopItems = new ConcurrentHashMap<>();

    public enum BuyResult {
        SUCCESS,
        NOT_FOUND,
        OUT_OF_STOCK,
        INSUFFICIENT_FUNDS,
        ERROR
    }

    public static class ShopItem {
        public String itemId;
        public double buyPrice;
        public double sellPrice;
        public int stock;
        public String category;
        public int minPermissionLevel;

        public ShopItem(String itemId, double buyPrice, double sellPrice, int stock) {
            this.itemId = itemId;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.stock = stock;
            this.category = "General";
            this.minPermissionLevel = 0;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("ShopManager.loadShop", () -> loadShop(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("ShopManager.saveShop", () -> saveShop(server)));
        Safe.run("ShopManager.initializeDefaultShop", ShopManager::initializeDefaultShop);
    }

    private static void initializeDefaultShop() {
        // Add some default items
        // Use stock = -1 to indicate infinite stock.
        shopItems.put("minecraft:diamond", new ShopItem("minecraft:diamond", 100.0, 80.0, -1));
        shopItems.put("minecraft:iron_ingot", new ShopItem("minecraft:iron_ingot", 10.0, 8.0, -1));
        shopItems.put("minecraft:gold_ingot", new ShopItem("minecraft:gold_ingot", 50.0, 40.0, -1));
        shopItems.put("minecraft:coal", new ShopItem("minecraft:coal", 2.0, 1.5, -1));

        shopItems.get("minecraft:diamond").category = "Materials";
        shopItems.get("minecraft:iron_ingot").category = "Materials";
        shopItems.get("minecraft:gold_ingot").category = "Materials";
        shopItems.get("minecraft:coal").category = "Materials";
    }

    private static void normalizeItem(ShopItem item) {
        if (item == null) return;
        if (item.category == null || item.category.isBlank()) item.category = "General";
        if (item.minPermissionLevel < 0) item.minPermissionLevel = 0;
        if (item.minPermissionLevel > 4) item.minPermissionLevel = 4;
    }

    public static boolean buyItem(UUID playerId, String itemId, int quantity) {
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        if (item.stock >= 0 && item.stock < quantity) return false;

        double totalCost = item.buyPrice * quantity;
        if (EconomyManager.getBalance(playerId).doubleValue() < totalCost) return false;

        if (EconomyManager.chargePlayer(playerId, BigDecimal.valueOf(totalCost), EconomyManager.TransactionType.SHOP_BUY,
                "Bought " + quantity + "x " + itemId)) {
            if (item.stock >= 0) item.stock -= quantity;
            saveShop(null);
            return true;
        }
        return false;
    }

    public static BuyResult buyAndDeliver(ServerPlayerEntity player, String itemId, int quantity) {
        try {
            if (player == null || itemId == null || quantity <= 0) return BuyResult.ERROR;
            ShopItem item = shopItems.get(itemId);
            normalizeItem(item);
            if (item == null) return BuyResult.NOT_FOUND;
            if (item.stock >= 0 && item.stock < quantity) return BuyResult.OUT_OF_STOCK;
            if (!hasMinPermissionLevel(player, item.minPermissionLevel)) return BuyResult.ERROR;

            BigDecimal totalCost = BigDecimal.valueOf(item.buyPrice).multiply(BigDecimal.valueOf(quantity));
            if (EconomyManager.getBalance(player.getUuid()).compareTo(totalCost) < 0) return BuyResult.INSUFFICIENT_FUNDS;

            boolean charged = EconomyManager.chargePlayer(player.getUuid(), totalCost, EconomyManager.TransactionType.SHOP_BUY,
                "Bought " + quantity + "x " + itemId);
            if (!charged) return BuyResult.INSUFFICIENT_FUNDS;

            if (item.stock >= 0) item.stock -= quantity;

            Item mcItem = Registries.ITEM.get(Identifier.of(itemId));
            int max = mcItem.getMaxCount();
            int remaining = quantity;
            while (remaining > 0) {
                int give = Math.min(remaining, Math.max(1, max));
                ItemStack stack = new ItemStack(mcItem, give);
                boolean inserted = player.getInventory().insertStack(stack);
                if (!inserted && !stack.isEmpty()) {
                    player.dropItem(stack, false);
                }
                remaining -= give;
            }

            saveShop(null);
            return BuyResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Shop purchase failed for {}", itemId, e);
            return BuyResult.ERROR;
        }
    }

    private static boolean hasMinPermissionLevel(ServerPlayerEntity player, int minLevel) {
        if (player == null) return false;
        PermissionLevel permLevel = switch (minLevel) {
            case 0 -> PermissionLevel.ALL;
            case 1 -> PermissionLevel.MODERATORS;
            case 2 -> PermissionLevel.ADMINS;
            default -> PermissionLevel.OWNERS;
        };
        return player.getCommandSource().getPermissions().hasPermission(new Permission.Level(permLevel));
    }

    public static double sellItem(UUID playerId, ItemStack itemStack) {
        String itemId = Registries.ITEM.getId(itemStack.getItem()).toString();
        ShopItem shopItem = shopItems.get(itemId);
        normalizeItem(shopItem);
        if (shopItem == null) return 0.0;

        double price = shopItem.sellPrice * itemStack.getCount();
        EconomyManager.rewardPlayer(playerId, BigDecimal.valueOf(price), EconomyManager.TransactionType.SHOP_SELL,
            "Sold " + itemStack.getCount() + "x " + itemId);

        if (shopItem.stock >= 0) {
            shopItem.stock += itemStack.getCount();
        }
        saveShop(null);
        return price;
    }

    public static Map<String, ShopItem> getShopItems() {
        Map<String, ShopItem> copy = new HashMap<>(shopItems);
        for (ShopItem item : copy.values()) {
            normalizeItem(item);
        }
        return copy;
    }

    public static ShopItem getShopItem(String itemId) {
        if (itemId == null) return null;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        return item;
    }

    public enum UpsertResult { SUCCESS, INVALID_ITEM, INVALID_PRICE, ERROR }

    public static UpsertResult upsertItem(String itemId, double buyPrice, double sellPrice, int stock) {
        return upsertItem(itemId, buyPrice, sellPrice, stock, "General", 0);
    }

    public static UpsertResult upsertItem(String itemId, double buyPrice, double sellPrice, int stock, String category, int minPermissionLevel) {
        try {
            if (itemId == null || itemId.isBlank()) return UpsertResult.INVALID_ITEM;
            if (buyPrice < 0 || sellPrice < 0) return UpsertResult.INVALID_PRICE;

            Identifier id = Identifier.of(itemId);
            if (!Registries.ITEM.containsId(id)) return UpsertResult.INVALID_ITEM;

            ShopItem item = new ShopItem(itemId, buyPrice, sellPrice, stock);
            item.category = category;
            item.minPermissionLevel = minPermissionLevel;
            normalizeItem(item);
            shopItems.put(itemId, item);
            saveShop(null);
            return UpsertResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Failed to upsert shop item {}", itemId, e);
            return UpsertResult.ERROR;
        }
    }

    public static UpsertResult updatePrices(String itemId, double buyPrice, double sellPrice) {
        if (itemId == null) return UpsertResult.INVALID_ITEM;
        if (buyPrice < 0 || sellPrice < 0) return UpsertResult.INVALID_PRICE;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return UpsertResult.INVALID_ITEM;
        item.buyPrice = buyPrice;
        item.sellPrice = sellPrice;
        saveShop(null);
        return UpsertResult.SUCCESS;
    }

    public static boolean updateCategory(String itemId, String category) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        item.category = category;
        normalizeItem(item);
        saveShop(null);
        return true;
    }

    public static boolean updateMinPermissionLevel(String itemId, int level) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        item.minPermissionLevel = level;
        normalizeItem(item);
        saveShop(null);
        return true;
    }

    public static boolean updateStock(String itemId, int stock) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        item.stock = stock;
        saveShop(null);
        return true;
    }

    public static boolean removeItem(String itemId) {
        if (itemId == null) return false;
        ShopItem removed = shopItems.remove(itemId);
        if (removed != null) {
            saveShop(null);
            return true;
        }
        return false;
    }

    public static void loadShop(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                if (!SHOP_FILE.exists()) {
                    saveShop(null);
                    return;
                }

                try (FileReader reader = new FileReader(SHOP_FILE)) {
                    Map<String, ShopItem> loadedItems = GSON.fromJson(reader, new TypeToken<Map<String, ShopItem>>(){}.getType());
                    if (loadedItems != null) {
                        for (ShopItem item : loadedItems.values()) {
                            normalizeItem(item);
                        }
                        shopItems.putAll(loadedItems);
                    }
                    LOGGER.info("Loaded {} shop items", shopItems.size());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load shop data", e);
            }
        });
    }

    public static void saveShop(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                try (FileWriter writer = new FileWriter(SHOP_FILE)) {
                    GSON.toJson(shopItems, writer);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to save shop data", e);
            }
        });
    }

    public static List<String> getCategories() {
        return shopItems.values().stream()
            .peek(ShopManager::normalizeItem)
            .map(item -> item.category)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
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
