package core.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import core.util.Safe;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import core.config.ConfigManager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EconomyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(ItemStack.class, new ItemStackAdapter())
        .setPrettyPrinting()
        .create();

    private static final File DATA_FILE = new File("economy.json");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    public enum Currency {
        COINS("Coins", true),
        TOKENS("Tokens", false),
        PREMIUM("Premium", false);

        public final String displayName;
        public final boolean usesConfigSymbol;

        Currency(String displayName, boolean usesConfigSymbol) {
            this.displayName = displayName;
            this.usesConfigSymbol = usesConfigSymbol;
        }

        public String getSymbol() {
            if (usesConfigSymbol) {
                return ConfigManager.getConfig().economy.currencySymbol;
            }
            return switch (this) {
                case TOKENS -> "T";
                case PREMIUM -> "P";
                default -> "";
            };
        }

        public static Currency parseOrNull(String raw) {
            if (raw == null) return null;
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) return null;
            try {
                return Currency.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private static final Map<UUID, EnumMap<Currency, BigDecimal>> playerBalances = new ConcurrentHashMap<>();
    private static final Map<String, TransactionHistory> transactionHistories = new ConcurrentHashMap<>();

    public enum TransactionType {
        PAY, SHOP_BUY, SHOP_SELL, CLAIM_COST, CLAIM_UPKEEP, BOUNTY, AUCTION_FEE, ADMIN_TAKE, ADMIN_GIVE, BANK_DEPOSIT, BANK_WITHDRAW, WANTED
    }

    public static class TransactionHistory {
        public List<Transaction> transactions = new ArrayList<>();
    }

    public static class Transaction {
        public long timestamp;
        public TransactionType type;
        public BigDecimal amount;
        public String description;
        public UUID fromPlayer;
        public UUID toPlayer;

        public Transaction(TransactionType type, BigDecimal amount, String description, UUID fromPlayer, UUID toPlayer) {
            this.timestamp = System.currentTimeMillis();
            this.type = type;
            this.amount = amount;
            this.description = description;
            this.fromPlayer = fromPlayer;
            this.toPlayer = toPlayer;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("EconomyManager.loadData", () -> loadData(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("EconomyManager.saveData", () -> saveData(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> Safe.run("EconomyManager.onServerTick", () -> onServerTick(server)));
    }

    private static void onServerTick(MinecraftServer server) {
        // Auto-save every 5 minutes
        if (server.getTicks() % (20 * 60 * 5) == 0) {
            EXECUTOR.submit(() -> saveData(null));
        }
    }

    public static void loadData(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                if (!DATA_FILE.exists()) {
                    LOGGER.info("Economy data file not found, creating new one");
                    saveData(null);
                    return;
                }

                try (FileReader reader = new FileReader(DATA_FILE)) {
                    JsonObject root = GSON.fromJson(reader, JsonObject.class);
                    if (root == null) {
                        throw new JsonSyntaxException("economy.json is empty or invalid");
                    }

                    if (root.has("balances") && root.get("balances").isJsonObject()) {
                        JsonObject balances = root.getAsJsonObject("balances");
                        for (String uuidKey : balances.keySet()) {
                            UUID playerId;
                            try {
                                playerId = UUID.fromString(uuidKey);
                            } catch (IllegalArgumentException ignored) {
                                continue;
                            }

                            JsonElement balanceElement = balances.get(uuidKey);
                            if (balanceElement == null || balanceElement.isJsonNull()) continue;

                            EnumMap<Currency, BigDecimal> currencyMap = new EnumMap<>(Currency.class);

                            // Backward compatibility: balances[uuid] = number
                            if (balanceElement.isJsonPrimitive() && balanceElement.getAsJsonPrimitive().isNumber()) {
                                currencyMap.put(Currency.COINS, BigDecimal.valueOf(balanceElement.getAsDouble()).setScale(2, RoundingMode.HALF_UP));
                            } else if (balanceElement.isJsonObject()) {
                                JsonObject currencyObj = balanceElement.getAsJsonObject();
                                for (String currencyKey : currencyObj.keySet()) {
                                    Currency currency = Currency.parseOrNull(currencyKey);
                                    if (currency == null) continue;
                                    JsonElement amountEl = currencyObj.get(currencyKey);
                                    if (amountEl == null || !amountEl.isJsonPrimitive() || !amountEl.getAsJsonPrimitive().isNumber()) continue;
                                    currencyMap.put(currency, BigDecimal.valueOf(amountEl.getAsDouble()).setScale(2, RoundingMode.HALF_UP));
                                }
                            }

                            if (!currencyMap.isEmpty()) {
                                playerBalances.put(playerId, currencyMap);
                            }
                        }
                    }

                    if (root.has("transactions") && root.get("transactions").isJsonObject()) {
                        Type type = new TypeToken<Map<String, TransactionHistory>>(){}.getType();
                        Map<String, TransactionHistory> transactions = GSON.fromJson(root.get("transactions"), type);
                        if (transactions != null) {
                            transactionHistories.putAll(transactions);
                        }
                    }

                    LOGGER.info("Loaded economy data for {} players", playerBalances.size());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load economy data", e);
                // Create backup and reset
                File backup = new File(DATA_FILE.getPath() + ".backup");
                try {
                    if (DATA_FILE.exists()) {
                        java.nio.file.Files.copy(DATA_FILE.toPath(), backup.toPath());
                        LOGGER.info("Created backup of corrupted economy data");
                    }
                } catch (IOException backupError) {
                    LOGGER.error("Failed to create backup", backupError);
                }

                playerBalances.clear();
                transactionHistories.clear();
                saveData(null);
            }
        });
    }

    public static void saveData(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                Map<String, Object> data = new HashMap<>();

                Map<String, Map<String, Double>> balances = new HashMap<>();
                for (Map.Entry<UUID, EnumMap<Currency, BigDecimal>> entry : playerBalances.entrySet()) {
                    Map<String, Double> currencyMap = new HashMap<>();
                    for (Map.Entry<Currency, BigDecimal> currencyEntry : entry.getValue().entrySet()) {
                        if (currencyEntry.getValue() == null) continue;
                        currencyMap.put(currencyEntry.getKey().name(), currencyEntry.getValue().doubleValue());
                    }
                    balances.put(entry.getKey().toString(), currencyMap);
                }
                data.put("balances", balances);
                data.put("transactions", transactionHistories);

                try (FileWriter writer = new FileWriter(DATA_FILE)) {
                    GSON.toJson(data, writer);
                }

                LOGGER.debug("Saved economy data for {} players", playerBalances.size());
            } catch (Exception e) {
                LOGGER.error("Failed to save economy data", e);
            }
        });
    }

    public static BigDecimal getBalance(UUID playerId) {
        return getBalance(playerId, Currency.COINS);
    }

    public static BigDecimal getBalance(UUID playerId, Currency currency) {
        if (playerId == null || currency == null) return BigDecimal.ZERO;
        EnumMap<Currency, BigDecimal> map = playerBalances.get(playerId);
        if (map != null && map.containsKey(currency)) {
            return map.get(currency);
        }
        if (currency == Currency.COINS) {
            return BigDecimal.valueOf(ConfigManager.getConfig().economy.defaultBalance).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public static Map<UUID, BigDecimal> snapshotBalances(Currency currency) {
        if (currency == null) currency = Currency.COINS;
        Map<UUID, BigDecimal> snapshot = new HashMap<>();
        for (UUID playerId : playerBalances.keySet()) {
            snapshot.put(playerId, getBalance(playerId, currency));
        }
        return snapshot;
    }

    public static void setBalance(UUID playerId, double amount) {
        setBalance(playerId, Currency.COINS, BigDecimal.valueOf(amount));
    }

    public static void setBalance(UUID playerId, Currency currency, BigDecimal amount) {
        if (playerId == null || currency == null || amount == null) return;
        EnumMap<Currency, BigDecimal> map = playerBalances.computeIfAbsent(playerId, k -> new EnumMap<>(Currency.class));
        map.put(currency, amount.setScale(2, RoundingMode.HALF_UP));
    }

    public static void addBalance(UUID playerId, double amount) {
        addBalance(playerId, Currency.COINS, BigDecimal.valueOf(amount));
    }

    public static void addBalance(UUID playerId, Currency currency, BigDecimal amount) {
        if (playerId == null || currency == null || amount == null) return;
        BigDecimal current = getBalance(playerId, currency);
        setBalance(playerId, currency, current.add(amount));
    }

    public static boolean chargePlayer(UUID playerId, BigDecimal amount, TransactionType type, String description) {
        return chargePlayer(playerId, Currency.COINS, amount, type, description);
    }

    public static boolean chargePlayer(UUID playerId, Currency currency, BigDecimal amount, TransactionType type, String description) {
        if (playerId == null || currency == null || amount == null) return false;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return true;

        BigDecimal current = getBalance(playerId, currency);
        if (current.compareTo(amount) < 0) return false;

        setBalance(playerId, currency, current.subtract(amount));
        addTransaction(playerId, type, amount.negate(), description, playerId, null);
        return true;
    }

    public static void rewardPlayer(UUID playerId, BigDecimal amount, TransactionType type, String description) {
        rewardPlayer(playerId, Currency.COINS, amount, type, description);
    }

    public static void rewardPlayer(UUID playerId, Currency currency, BigDecimal amount, TransactionType type, String description) {
        if (playerId == null || currency == null || amount == null) return;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal current = getBalance(playerId, currency);
        setBalance(playerId, currency, current.add(amount));
        addTransaction(playerId, type, amount, description, null, playerId);
    }

    public static boolean transfer(UUID fromPlayer, UUID toPlayer, BigDecimal amount, TransactionType type, String description) {
        return transfer(fromPlayer, toPlayer, Currency.COINS, amount, type, description);
    }

    public static boolean transfer(UUID fromPlayer, UUID toPlayer, Currency currency, BigDecimal amount, TransactionType type, String description) {
        if (fromPlayer == null || toPlayer == null || currency == null || amount == null) return false;
        if (fromPlayer.equals(toPlayer)) return false;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return false;

        BigDecimal fromBalance = getBalance(fromPlayer, currency);
        if (fromBalance.compareTo(amount) < 0) return false;

        setBalance(fromPlayer, currency, fromBalance.subtract(amount));
        BigDecimal toBalance = getBalance(toPlayer, currency);
        setBalance(toPlayer, currency, toBalance.add(amount));

        addTransaction(fromPlayer, type, amount.negate(), description, fromPlayer, toPlayer);
        addTransaction(toPlayer, type, amount, description, fromPlayer, toPlayer);
        return true;
    }

    public static boolean depositToBank(UUID playerId, BigDecimal amount) {
        return chargePlayer(playerId, amount, TransactionType.BANK_DEPOSIT, "Bank deposit");
    }

    private static void addTransaction(UUID playerId, TransactionType type, BigDecimal amount, String description, UUID fromPlayer, UUID toPlayer) {
        TransactionHistory history = transactionHistories.computeIfAbsent(playerId.toString(), k -> new TransactionHistory());
        history.transactions.add(new Transaction(type, amount, description, fromPlayer, toPlayer));

        // Keep only last 100 transactions per player
        if (history.transactions.size() > 100) {
            history.transactions = history.transactions.subList(history.transactions.size() - 100, history.transactions.size());
        }
    }

    public static String formatCurrency(double amount) {
        return formatCurrency(BigDecimal.valueOf(amount), Currency.COINS, false);
    }

    public static String formatCurrency(BigDecimal amount) {
        return formatCurrency(amount, Currency.COINS, false);
    }

    public static String formatCurrency(BigDecimal amount, Currency currency) {
        return formatCurrency(amount, currency, false);
    }

    public static String formatCurrencyCompact(BigDecimal amount, Currency currency) {
        return formatCurrency(amount, currency, true);
    }

    private static String formatCurrency(BigDecimal amount, Currency currency, boolean compact) {
        if (amount == null) amount = BigDecimal.ZERO;
        if (currency == null) currency = Currency.COINS;

        String symbol = currency.getSymbol();
        if (!compact) {
            DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
            return symbol + df.format(amount.setScale(2, RoundingMode.HALF_UP));
        }

        BigDecimal abs = amount.abs();
        BigDecimal thousand = BigDecimal.valueOf(1_000);
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        BigDecimal billion = BigDecimal.valueOf(1_000_000_000);
        BigDecimal trillion = BigDecimal.valueOf(1_000_000_000_000L);

        String suffix = "";
        BigDecimal divisor = BigDecimal.ONE;
        if (abs.compareTo(trillion) >= 0) {
            suffix = "T";
            divisor = trillion;
        } else if (abs.compareTo(billion) >= 0) {
            suffix = "B";
            divisor = billion;
        } else if (abs.compareTo(million) >= 0) {
            suffix = "M";
            divisor = million;
        } else if (abs.compareTo(thousand) >= 0) {
            suffix = "K";
            divisor = thousand;
        }

        BigDecimal scaled = amount.divide(divisor, 2, RoundingMode.HALF_UP);
        DecimalFormat df = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return symbol + df.format(scaled) + suffix;
    }

    // Custom ItemStack adapter for Gson
    public static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
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
