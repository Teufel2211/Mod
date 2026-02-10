package core.bounty;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import core.util.Safe;
import net.minecraft.server.MinecraftServer;
import core.config.ConfigManager;
import core.clans.ClanManager;
import core.discord.DiscordManager;
import core.economy.EconomyManager;
import core.wanted.WantedManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BountyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File BOUNTIES_FILE = new File("data/core-bounties.json");

    public enum WantedReason {
        PLAYER_KILL, THEFT, RULE_VIOLATION, CHEATING
    }

    public static class Bounty {
        public UUID targetId;
        public UUID setterId;
        public double amount;
        public long setAt;
        public String reason;
        public boolean anonymous;

        public Bounty(UUID targetId, UUID setterId, double amount, String reason, boolean anonymous) {
            this.targetId = targetId;
            this.setterId = setterId;
            this.amount = amount;
            this.setAt = System.currentTimeMillis();
            this.reason = reason;
            this.anonymous = anonymous;
        }
    }

    private static final Map<UUID, List<Bounty>> bounties = new ConcurrentHashMap<>();

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("BountyManager.loadData", () -> loadData(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("BountyManager.saveData", () -> saveData(server)));
        // Wanted system is handled by core.wanted.WantedManager. We keep bounties focused on payouts.
    }

    // Bounty Management
    public static boolean placeBounty(UUID targetId, UUID setterId, double amount, String reason, boolean anonymous) {
        if (targetId.equals(setterId)) return false; // Can't bounty yourself
        if (amount < ConfigManager.getConfig().bounty.minBountyAmount) return false;
        if (amount > ConfigManager.getConfig().bounty.maxBountyAmount) return false;

        // Check if setter is linked to Discord (requirement)
        if (ConfigManager.getConfig().bounty.requireDiscordLink && !DiscordManager.isLinked(setterId)) {
            return false;
        }

        double fee = amount * ConfigManager.getConfig().bounty.bountyFeePercent;
        double totalCost = amount + fee;

        if (EconomyManager.getBalance(setterId).doubleValue() < totalCost) return false;

        // Check clan bounty limit
        String setterClan = ClanManager.getPlayerClanName(setterId);
        if (setterClan != null && getClanBountyCount(setterClan) >= ConfigManager.getConfig().bounty.maxBountiesPerClan) {
            return false;
        }

        // Charge the fee and amount
        EconomyManager.chargePlayer(setterId, BigDecimal.valueOf(totalCost), EconomyManager.TransactionType.BOUNTY, "Bounty placement fee");

        Bounty bounty = new Bounty(targetId, setterId, amount, reason, anonymous);
        bounties.computeIfAbsent(targetId, k -> new ArrayList<>()).add(bounty);

        // Discord notification
        String setterName = anonymous ? "Anonymous" : DiscordManager.getDiscordId(setterId);
        DiscordManager.sendBountyFeed("🎯 Bounty placed on <@" + DiscordManager.getDiscordId(targetId) + ">\n" +
            "Amount: " + EconomyManager.formatCurrency(amount) + "\n" +
            "Setter: " + (anonymous ? "Anonymous" : "<@" + setterName + ">") + "\n" +
            "Reason: " + reason);

        saveData(null);
        return true;
    }

    /**
     * Place a "system" bounty that doesn't charge any player and doesn't require Discord linking.
     * Used for automated punishments (e.g. illegal kills in claims).
     */
    public static void placeSystemBounty(UUID targetId, double amount, String reason) {
        if (targetId == null) return;
        double clamped = Math.max(ConfigManager.getConfig().bounty.minBountyAmount, Math.min(amount, ConfigManager.getConfig().bounty.maxBountyAmount));
        Bounty bounty = new Bounty(targetId, new UUID(0L, 0L), clamped, reason == null ? "System bounty" : reason, true);
        bounties.computeIfAbsent(targetId, k -> new ArrayList<>()).add(bounty);
        Safe.run("BountyManager.placeSystemBounty.discord", () ->
            DiscordManager.sendBountyFeed("⚠️ System bounty placed on <@" + DiscordManager.getDiscordId(targetId) + ">\n" +
                "Amount: " + EconomyManager.formatCurrency(clamped) + "\n" +
                "Reason: " + (reason == null ? "System" : reason)));
        saveData(null);
    }

    public static double claimBounty(UUID targetId, UUID killerId) {
        if (targetId.equals(killerId)) return 0.0; // Can't claim bounty on yourself

        // Check clan restrictions
        String killerClan = ClanManager.getPlayerClanName(killerId);
        String targetClan = ClanManager.getPlayerClanName(targetId);
        if (Objects.equals(killerClan, targetClan) && killerClan != null) {
            return 0.0; // Can't claim bounty on clan mate
        }

        List<Bounty> targetBounties = bounties.get(targetId);
        if (targetBounties == null || targetBounties.isEmpty()) return 0.0;

        double totalReward = 0.0;

        // Calculate reward with wanted bonus
        int wantedLevel = WantedManager.getWantedLevel(targetId);
        double multiplier = wantedLevel <= 0 ? 1.0 : (1.0 + (wantedLevel - 1) * 0.25);

        for (Bounty bounty : targetBounties) {
            double reward = bounty.amount * multiplier;
            totalReward += reward;
        }

        // Clear bounties
        bounties.remove(targetId);

        // Discord notification
        DiscordManager.sendBountyFeed("💰 Bounty claimed on <@" + DiscordManager.getDiscordId(targetId) + ">!\n" +
            "Killer: <@" + DiscordManager.getDiscordId(killerId) + ">\n" +
            "Total Reward: " + EconomyManager.formatCurrency(totalReward) +
            (multiplier > 1.0 ? " (Wanted bonus: " + String.format("%.1fx", multiplier) + ")" : ""));

        // Pay the killer (or their clan bank).
        String killerClanPayout = ClanManager.getPlayerClanName(killerId);
        if (killerClanPayout != null && ConfigManager.getConfig().bounty.clanBountiesGoToBank) {
            ClanManager.depositToClanBank(killerId, killerClanPayout, totalReward);
        } else {
            EconomyManager.rewardPlayer(killerId, BigDecimal.valueOf(totalReward), EconomyManager.TransactionType.BOUNTY, "Bounty reward");
        }

        saveData(null);
        return totalReward;
    }

    // Getters
    public static double getTotalBounty(UUID targetId) {
        List<Bounty> targetBounties = bounties.get(targetId);
        if (targetBounties == null) return 0.0;

        return targetBounties.stream().mapToDouble(b -> b.amount).sum();
    }

    public static List<Bounty> getBounties(UUID targetId) {
        return new ArrayList<>(bounties.getOrDefault(targetId, new ArrayList<>()));
    }

    public static void clearBounties(UUID targetId) {
        if (targetId == null) return;
        if (bounties.remove(targetId) != null) {
            saveData(null);
        }
    }

    // Wanted levels are provided by WantedManager.

    public static Map<UUID, Double> getAllBounties() {
        Map<UUID, Double> result = new HashMap<>();
        for (Map.Entry<UUID, List<Bounty>> entry : bounties.entrySet()) {
            result.put(entry.getKey(), getTotalBounty(entry.getKey()));
        }
        return result;
    }

    // Utility Methods
    private static int getClanBountyCount(String clanName) {
        int count = 0;
        for (List<Bounty> bountyList : bounties.values()) {
            for (Bounty bounty : bountyList) {
                String setterClan = ClanManager.getPlayerClanName(bounty.setterId);
                if (Objects.equals(setterClan, clanName)) {
                    count++;
                }
            }
        }
        return count;
    }

    // Persistence
    private static void loadData(MinecraftServer server) {
        // Load bounties
        if (BOUNTIES_FILE.exists()) {
            try (FileReader reader = new FileReader(BOUNTIES_FILE)) {
                Type type = new TypeToken<Map<String, List<Bounty>>>(){}.getType();
                Map<String, List<Bounty>> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    for (Map.Entry<String, List<Bounty>> entry : loaded.entrySet()) {
                        bounties.put(UUID.fromString(entry.getKey()), entry.getValue());
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load bounties", e);
            }
        }
    }

    private static void saveData(MinecraftServer server) {
        try {
            // Save bounties
            BOUNTIES_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(BOUNTIES_FILE)) {
                Map<String, List<Bounty>> data = new HashMap<>();
                for (Map.Entry<UUID, List<Bounty>> entry : bounties.entrySet()) {
                    data.put(entry.getKey().toString(), entry.getValue());
                }
                GSON.toJson(data, writer);
            }

        } catch (IOException e) {
            LOGGER.error("Failed to save bounty data", e);
        }
    }
}
