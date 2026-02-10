package core.clans;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import core.util.Safe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import core.config.ConfigManager;
import core.discord.DiscordManager;
import core.economy.EconomyManager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClanManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CLANS_FILE = new File("data/core-clans.json");

    public enum ClanRole {
        LEADER, OFFICER, MEMBER
    }

    public enum ClanPermission {
        INVITE_MEMBERS, KICK_MEMBERS, MANAGE_BANK, SET_HOME, CLAIM_LAND, UPGRADE_CLAN
    }

    public static class Clan {
        public String name;
        public String tag;
        public int color;
        public int level;
        public double bankBalance;
        public UUID leader;
        public Map<UUID, ClanRole> members;
        public Set<ClanPermission> permissions;
        public Map<String, Object> upgrades;
        public String discordChannelId;
        public long createdAt;

        public Clan(String name, String tag, UUID leader) {
            this.name = name;
            this.tag = tag;
            this.color = 0xFFFFFF;
            this.level = 1;
            this.bankBalance = 0.0;
            this.leader = leader;
            this.members = new ConcurrentHashMap<>();
            this.members.put(leader, ClanRole.LEADER);
            this.permissions = new HashSet<>();
            this.upgrades = new HashMap<>();
            this.createdAt = System.currentTimeMillis();
        }

        public boolean hasPermission(UUID playerId, ClanPermission permission) {
            ClanRole role = members.get(playerId);
            if (role == null) return false;

            switch (role) {
                case LEADER -> { return true; }
                case OFFICER -> {
                    return permission != ClanPermission.UPGRADE_CLAN; // Only leader can upgrade
                }
                case MEMBER -> {
                    return permission == ClanPermission.CLAIM_LAND; // Members can only claim land
                }
            }
            return false;
        }

        public double getUpgradeCost(String upgrade) {
            return switch (upgrade) {
                case "max_members" -> 1000.0 * level;
                case "claim_radius" -> 500.0 * level;
                case "tax_reduction" -> 2000.0 * level;
                default -> 0.0;
            };
        }

        public boolean canAffordUpgrade(String upgrade) {
            return bankBalance >= getUpgradeCost(upgrade);
        }

        public void applyUpgrade(String upgrade) {
            double cost = getUpgradeCost(upgrade);
            if (bankBalance >= cost) {
                bankBalance -= cost;
                upgrades.put(upgrade, (int)upgrades.getOrDefault(upgrade, 0) + 1);
                level++;
            }
        }
    }

    private static final Map<String, Clan> clans = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerClans = new ConcurrentHashMap<>();
    private static final Map<UUID, ClanInvite> pendingInvites = new ConcurrentHashMap<>();
    private static volatile MinecraftServer serverRef;

    private static class ClanInvite {
        public final String clanName;
        public final UUID inviter;
        public final long expiresAt;

        public ClanInvite(String clanName, UUID inviter, long expiresAt) {
            this.clanName = clanName;
            this.inviter = inviter;
            this.expiresAt = expiresAt;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("ClanManager.onServerStarted", () -> {
            serverRef = server;
            loadClans(server);
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("ClanManager.saveClans", () -> saveClans(server)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Safe.run("ClanManager.onJoin", () -> {
            // Send clan info to player
            String clanName = playerClans.get(handler.player.getUuid());
            if (clanName != null) {
                Clan clan = clans.get(clanName);
                if (clan != null) {
                    handler.player.sendMessage(Text.literal(
                        "§7Welcome back to clan §" + String.format("%x", clan.color) + clan.name + " §7[" + clan.tag + "]"), false);
                }
            }
        }));
    }

    // Clan Creation & Management
    public static boolean createClan(String name, String tag, UUID creator) {
        if (clans.containsKey(name.toLowerCase()) || playerClans.containsKey(creator)) {
            return false;
        }

        double creationCost = ConfigManager.getConfig().clan.creationCost;
        if (EconomyManager.getBalance(creator).doubleValue() < creationCost) {
            return false;
        }

        EconomyManager.chargePlayer(creator, BigDecimal.valueOf(creationCost), EconomyManager.TransactionType.CLAIM_COST, "Clan creation fee");

        Clan clan = new Clan(name, tag, creator);
        clans.put(name.toLowerCase(), clan);
        playerClans.put(creator, name.toLowerCase());

        // Discord integration
        DiscordManager.sendClanEvent("🏰 New clan created: **" + name + "** [" + tag + "]\nLeader: <@" + DiscordManager.getDiscordId(creator) + ">");

        saveClans(null);
        return true;
    }

    public static boolean invitePlayer(UUID inviter, UUID target, String clanName) {
        Clan clan = clans.get(clanName.toLowerCase());
        if (clan == null || !clan.hasPermission(inviter, ClanPermission.INVITE_MEMBERS)) {
            return false;
        }

        if (playerClans.containsKey(target) || clan.members.size() >= getMaxMembers(clan)) {
            return false;
        }

        long expiryMs = 10 * 60 * 1000L;
        pendingInvites.put(target, new ClanInvite(clan.name, inviter, System.currentTimeMillis() + expiryMs));

        ServerPlayerEntity targetPlayer = serverRef != null ? serverRef.getPlayerManager().getPlayer(target) : null;
        if (targetPlayer != null) {
            targetPlayer.sendMessage(Text.literal(
                "§aYou have been invited to join clan §e" + clan.name + " §a[" + clan.tag + "] §aby " +
                getPlayerName(inviter) + ". Use /clan join " + clan.name), false);
        }

        DiscordManager.sendClanEvent("✉️ **" + getPlayerName(target) + "** was invited to clan **" + clan.name + "** by **" + getPlayerName(inviter) + "**");
        return true;
    }

    public static boolean joinClan(UUID player, String clanName) {
        Clan clan = clans.get(clanName.toLowerCase());
        if (clan == null || playerClans.containsKey(player) || clan.members.size() >= getMaxMembers(clan)) {
            return false;
        }

        ClanInvite invite = pendingInvites.get(player);
        if (invite != null && System.currentTimeMillis() > invite.expiresAt) {
            pendingInvites.remove(player);
        }

        clan.members.put(player, ClanRole.MEMBER);
        playerClans.put(player, clanName.toLowerCase());

        DiscordManager.sendClanEvent("👥 **" + getPlayerName(player) + "** joined clan **" + clan.name + "**");

        saveClans(null);
        return true;
    }

    public static boolean leaveClan(UUID player) {
        String clanName = playerClans.get(player);
        if (clanName == null) return false;

        Clan clan = clans.get(clanName);
        if (clan == null) return false;

        if (clan.leader.equals(player)) {
            // Transfer leadership or disband clan
            if (clan.members.size() == 1) {
                // Disband clan
                clans.remove(clanName);
                DiscordManager.sendClanEvent("🏰 Clan **" + clan.name + "** has been disbanded");
            } else {
                // Transfer to highest officer
                UUID newLeader = null;
                for (Map.Entry<UUID, ClanRole> entry : clan.members.entrySet()) {
                    if (entry.getValue() == ClanRole.OFFICER && !entry.getKey().equals(player)) {
                        newLeader = entry.getKey();
                        break;
                    }
                }
                if (newLeader == null) {
                    // Transfer to random member
                    for (Map.Entry<UUID, ClanRole> entry : clan.members.entrySet()) {
                        if (!entry.getKey().equals(player)) {
                            newLeader = entry.getKey();
                            break;
                        }
                    }
                }

                if (newLeader != null) {
                    clan.leader = newLeader;
                    clan.members.put(newLeader, ClanRole.LEADER);
                    DiscordManager.sendClanEvent("👑 **" + getPlayerName(newLeader) + "** is now the leader of clan **" + clan.name + "**");
                }
            }
        }

        clan.members.remove(player);
        playerClans.remove(player);

        DiscordManager.sendClanEvent("👋 **" + getPlayerName(player) + "** left clan **" + clan.name + "**");

        saveClans(null);
        return true;
    }

    // Clan Banking
    public static boolean depositToClanBank(UUID player, String clanName, double amount) {
        Clan clan = clans.get(clanName.toLowerCase());
        if (clan == null || !clan.members.containsKey(player)) return false;

        if (EconomyManager.transfer(player, clan.leader, BigDecimal.valueOf(amount), EconomyManager.TransactionType.BANK_DEPOSIT, "Clan bank deposit")) {
            clan.bankBalance += amount;
            saveClans(null);
            return true;
        }
        return false;
    }

    public static boolean withdrawFromClanBank(UUID player, String clanName, double amount) {
        Clan clan = clans.get(clanName.toLowerCase());
        if (clan == null || !clan.hasPermission(player, ClanPermission.MANAGE_BANK) || clan.bankBalance < amount) {
            return false;
        }

        clan.bankBalance -= amount;
        EconomyManager.rewardPlayer(player, BigDecimal.valueOf(amount), EconomyManager.TransactionType.BANK_WITHDRAW, "Clan bank withdrawal");
        saveClans(null);
        return true;
    }

    // Clan Upgrades
    public static boolean upgradeClan(UUID player, String clanName, String upgrade) {
        Clan clan = clans.get(clanName.toLowerCase());
        if (clan == null || !clan.leader.equals(player)) return false;

        if (clan.canAffordUpgrade(upgrade)) {
            clan.applyUpgrade(upgrade);
            DiscordManager.sendClanEvent("⬆️ Clan **" + clan.name + "** upgraded: " + upgrade);
            saveClans(null);
            return true;
        }
        return false;
    }

    // Getters
    public static Clan getClan(String name) {
        return clans.get(name.toLowerCase());
    }

    public static Clan getPlayerClan(UUID player) {
        String clanName = playerClans.get(player);
        return clanName != null ? clans.get(clanName) : null;
    }

    public static String getPlayerClanName(UUID player) {
        return playerClans.get(player);
    }

    public static Map<String, Clan> getAllClans() {
        return new HashMap<>(clans);
    }

    public static int getMaxMembers(Clan clan) {
        return 5 + ((int)clan.upgrades.getOrDefault("max_members", 0) * 2);
    }

    public static int getClaimRadius(Clan clan) {
        return 16 + ((int)clan.upgrades.getOrDefault("claim_radius", 0) * 8);
    }

    public static double getTaxReduction(Clan clan) {
        return ((int)clan.upgrades.getOrDefault("tax_reduction", 0)) * 0.05; // 5% per level
    }

    // Utility
    private static String getPlayerName(UUID playerId) {
        if (serverRef != null) {
            ServerPlayerEntity player = serverRef.getPlayerManager().getPlayer(playerId);
            if (player != null) return player.getName().getString();
        }
        return "Player" + playerId.toString().substring(0, 8);
    }

    private static void loadClans(MinecraftServer server) {
        if (CLANS_FILE.exists()) {
            try (FileReader reader = new FileReader(CLANS_FILE)) {
                Type type = new TypeToken<Map<String, Clan>>(){}.getType();
                Map<String, Clan> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    clans.putAll(loaded);
                    // Rebuild player clans map
                    for (Map.Entry<String, Clan> entry : clans.entrySet()) {
                        Clan clan = entry.getValue();
                        for (UUID member : clan.members.keySet()) {
                            playerClans.put(member, entry.getKey());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load clans", e);
            }
        }
    }

    private static void saveClans(MinecraftServer server) {
        try {
            CLANS_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CLANS_FILE)) {
                GSON.toJson(clans, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save clans", e);
        }
    }
}
