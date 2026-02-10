package core.claims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import core.util.Safe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import core.config.ConfigManager;
import core.clans.ClanManager;
import core.economy.EconomyManager;
import core.discord.DiscordManager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class ClaimManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CLAIMS_FILE = new File("data/core-claims.json");

    public enum ClaimType {
        PLAYER, CLAN, ADMIN
    }

    public enum ClaimPermission {
        BUILD, BREAK, CONTAINER, REDSTONE, PVP, EXPLOSIONS
    }

    public static class Claim {
        public ClaimType type;
        public UUID ownerId; // Player UUID or Clan Leader UUID
        public String clanName; // For clan claims
        public long claimedAt;
        public long lastPaid; // For upkeep tracking
        public Map<ClaimPermission, Boolean> permissions;
        public boolean isOverdue;

        public Claim(ClaimType type, UUID ownerId) {
            this.type = type;
            this.ownerId = ownerId;
            this.claimedAt = System.currentTimeMillis();
            this.lastPaid = System.currentTimeMillis();
            this.permissions = new HashMap<>();
            this.isOverdue = false;

            // Default permissions
            permissions.put(ClaimPermission.BUILD, true);
            permissions.put(ClaimPermission.BREAK, true);
            permissions.put(ClaimPermission.CONTAINER, false);
            permissions.put(ClaimPermission.REDSTONE, false);
            permissions.put(ClaimPermission.PVP, true);
            permissions.put(ClaimPermission.EXPLOSIONS, false);
        }

        public double getUpkeepCost() {
            long daysSinceLastPaid = (System.currentTimeMillis() - lastPaid) / (1000 * 60 * 60 * 24);
            double baseCost = ConfigManager.getConfig().claim.upkeepCostPerDay;

            // Clan claims get tax reduction
            if (type == ClaimType.CLAN && clanName != null) {
                ClanManager.Clan clan = ClanManager.getClan(clanName);
                if (clan != null) {
                    baseCost *= (1.0 - ClanManager.getTaxReduction(clan));
                }
            }

            return baseCost * Math.max(1, daysSinceLastPaid);
        }

        public boolean canAffordUpkeep() {
            double cost = getUpkeepCost();
            switch (type) {
                case PLAYER -> {
                    return EconomyManager.getBalance(ownerId).doubleValue() >= cost;
                }
                case CLAN -> {
                    if (clanName != null) {
                        ClanManager.Clan clan = ClanManager.getClan(clanName);
                        return clan != null && clan.bankBalance >= cost;
                    }
                }
                case ADMIN -> {
                    return true; // Admin claims don't require payment
                }
            }
            return false;
        }

        public void payUpkeep() {
            double cost = getUpkeepCost();
            boolean paid = false;

            switch (type) {
                case PLAYER -> {
                    paid = EconomyManager.chargePlayer(ownerId, BigDecimal.valueOf(cost), EconomyManager.TransactionType.CLAIM_UPKEEP, "Claim upkeep");
                }
                case CLAN -> {
                    if (clanName != null) {
                        ClanManager.Clan clan = ClanManager.getClan(clanName);
                        if (clan != null && clan.bankBalance >= cost) {
                            clan.bankBalance -= cost;
                            paid = true;
                        }
                    }
                }
                case ADMIN -> {
                    paid = true; // Admin claims don't require payment
                }
            }

            if (paid) {
                lastPaid = System.currentTimeMillis();
                isOverdue = false;
            } else {
                isOverdue = true;
            }
        }
    }

    private static final Map<String, Claim> claims = new ConcurrentHashMap<>(); // Chunk key to claim
    private static final Map<UUID, Set<String>> playerClaims = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> clanClaims = new ConcurrentHashMap<>();

    private static boolean isEnabled() {
        return ConfigManager.getConfig().claim.enableClaims;
    }

    public static void init() {
        if (!isEnabled()) {
            LOGGER.info("Claims are disabled in config, skipping initialization");
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("ClaimManager.loadClaims", () -> loadClaims(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("ClaimManager.saveClaims", () -> saveClaims(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> Safe.run("ClaimManager.processUpkeep", () -> processUpkeep(server)));
    }

    // Claim Management
    public static boolean claimChunk(UUID playerId, ChunkPos pos, ClaimType type) {
        if (!isEnabled()) return false;
        String key = getChunkKey(pos);
        if (claims.containsKey(key)) return false;

        // Check claim limits
        int maxClaims = getMaxClaims(playerId, type);
        int currentClaims = getPlayerClaimCount(playerId);
        if (currentClaims >= maxClaims) return false;

        // Check distance from other claims
        if (!canClaimAt(pos, playerId, type)) return false;

        // Check cost
        double cost = getClaimCost(type);
        if (!canAffordClaim(playerId, type, cost)) return false;

        // Charge for claim
        chargeForClaim(playerId, type, cost);

        Claim claim = new Claim(type, playerId);
        if (type == ClaimType.CLAN) {
            String clanName = ClanManager.getPlayerClanName(playerId);
            if (clanName != null) {
                claim.clanName = clanName;
                clanClaims.computeIfAbsent(clanName, k -> new HashSet<>()).add(key);
            }
        }

        claims.put(key, claim);
        playerClaims.computeIfAbsent(playerId, k -> new HashSet<>()).add(key);

        saveClaims(null);
        return true;
    }

    public static boolean unclaimChunk(UUID playerId, ChunkPos pos) {
        if (!isEnabled()) return false;
        String key = getChunkKey(pos);
        Claim claim = claims.get(key);
        if (claim == null) return false;

        // Check ownership
        if (!canModifyClaim(playerId, claim)) return false;

        claims.remove(key);
        playerClaims.getOrDefault(playerId, new HashSet<>()).remove(key);

        if (claim.type == ClaimType.CLAN && claim.clanName != null) {
            clanClaims.getOrDefault(claim.clanName, new HashSet<>()).remove(key);
        }

        saveClaims(null);
        return true;
    }

    // Permission Checking
    public static boolean hasPermission(UUID playerId, ChunkPos pos, ClaimPermission permission) {
        if (!isEnabled()) return true; // Allow everything if claims disabled
        Claim claim = claims.get(getChunkKey(pos));
        if (claim == null) return true; // Unclaimed chunks allow everything

        if (claim.isOverdue) return true; // Overdue claims allow everything

        // Owner always has permission
        if (claim.ownerId.equals(playerId)) return true;

        // Clan members have permissions based on their role
        if (claim.type == ClaimType.CLAN && claim.clanName != null) {
            ClanManager.Clan clan = ClanManager.getClan(claim.clanName);
            if (clan != null && clan.members.containsKey(playerId)) {
                // Clan members can build/break by default
                return permission == ClaimPermission.BUILD || permission == ClaimPermission.BREAK ||
                       claim.permissions.getOrDefault(permission, false);
            }
        }

        // Check explicit permissions
        return claim.permissions.getOrDefault(permission, false);
    }

    public static boolean canModifyClaim(UUID playerId, Claim claim) {
        if (!isEnabled()) return false;
        if (claim.ownerId.equals(playerId)) return true;

        if (claim.type == ClaimType.CLAN && claim.clanName != null) {
            ClanManager.Clan clan = ClanManager.getClan(claim.clanName);
            return clan != null && clan.hasPermission(playerId, ClanManager.ClanPermission.CLAIM_LAND);
        }

        return false;
    }

    // Permission Management
    public static boolean setClaimPermission(UUID playerId, ChunkPos pos, ClaimPermission permission, boolean allowed) {
        if (!isEnabled()) return false;
        String key = getChunkKey(pos);
        Claim claim = claims.get(key);
        if (claim == null || !canModifyClaim(playerId, claim)) return false;

        claim.permissions.put(permission, allowed);
        saveClaims(null);
        return true;
    }

    // Utility Methods
    public static Claim getClaim(ChunkPos pos) {
        if (!isEnabled()) return null;
        return claims.get(getChunkKey(pos));
    }

    public static boolean isClaimed(ChunkPos pos) {
        if (!isEnabled()) return false;
        return claims.containsKey(getChunkKey(pos));
    }

    public static ClaimType getClaimType(ChunkPos pos) {
        if (!isEnabled()) return null;
        Claim claim = claims.get(getChunkKey(pos));
        return claim != null ? claim.type : null;
    }

    public static UUID getOwner(ChunkPos pos) {
        if (!isEnabled()) return null;
        Claim claim = claims.get(getChunkKey(pos));
        return claim != null ? claim.ownerId : null;
    }

    public static int getPlayerClaimCount(UUID playerId) {
        if (!isEnabled()) return 0;
        return playerClaims.getOrDefault(playerId, new HashSet<>()).size();
    }

    public static Set<String> getPlayerClaims(UUID playerId) {
        if (!isEnabled()) return Collections.emptySet();
        return new HashSet<>(playerClaims.getOrDefault(playerId, new HashSet<>()));
    }

    public static Set<String> getClanClaims(String clanName) {
        if (!isEnabled()) return Collections.emptySet();
        return new HashSet<>(clanClaims.getOrDefault(clanName, new HashSet<>()));
    }

    // Economic Integration
    private static double getClaimCost(ClaimType type) {
        return switch (type) {
            case PLAYER -> ConfigManager.getConfig().claim.playerClaimCost;
            case CLAN -> ConfigManager.getConfig().claim.clanClaimCost;
            case ADMIN -> 0.0;
        };
    }

    private static boolean canAffordClaim(UUID playerId, ClaimType type, double cost) {
        if (type == ClaimType.ADMIN) return true;

        if (type == ClaimType.CLAN) {
            String clanName = ClanManager.getPlayerClanName(playerId);
            if (clanName != null) {
                ClanManager.Clan clan = ClanManager.getClan(clanName);
                return clan != null && clan.bankBalance >= cost;
            }
            return false;
        }

        return EconomyManager.getBalance(playerId).doubleValue() >= cost;
    }

    private static void chargeForClaim(UUID playerId, ClaimType type, double cost) {
        if (type == ClaimType.ADMIN || cost <= 0) return;

        if (type == ClaimType.CLAN) {
            ClanManager.depositToClanBank(playerId, ClanManager.getPlayerClanName(playerId), -cost);
        } else {
            EconomyManager.chargePlayer(playerId, BigDecimal.valueOf(cost), EconomyManager.TransactionType.CLAIM_COST, "Claim purchase");
        }
    }

    private static int getMaxClaims(UUID playerId, ClaimType type) {
        int base = ConfigManager.getConfig().claim.maxPlayerClaims;

        if (type == ClaimType.CLAN) {
            String clanName = ClanManager.getPlayerClanName(playerId);
            if (clanName != null) {
                ClanManager.Clan clan = ClanManager.getClan(clanName);
                if (clan != null) {
                    base += clan.level * 2; // Clan level bonus
                }
            }
        }

        return base;
    }

    private static boolean canClaimAt(ChunkPos pos, UUID playerId, ClaimType type) {
        // Check minimum distance from other claims
        int minDistance = ConfigManager.getConfig().claim.minClaimDistance;

        for (int x = pos.x - minDistance; x <= pos.x + minDistance; x++) {
            for (int z = pos.z - minDistance; z <= pos.z + minDistance; z++) {
                if (x == pos.x && z == pos.z) continue;

                Claim nearby = claims.get(x + "," + z);
                if (nearby != null) {
                    // Allow clan members to claim adjacent chunks
                    if (type == ClaimType.CLAN && nearby.type == ClaimType.CLAN &&
                        Objects.equals(nearby.clanName, ClanManager.getPlayerClanName(playerId))) {
                        continue;
                    }

                    // Check if owned by same player/clan
                    if (!nearby.ownerId.equals(playerId)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    // Upkeep Processing
    private static void processUpkeep(MinecraftServer server) {
        // Process upkeep every hour
        if (server.getTicks() % (20 * 60 * 60) != 0) return;

        List<String> overdueClaims = new ArrayList<>();

        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.type == ClaimType.ADMIN) continue;

            if (!claim.canAffordUpkeep()) {
                claim.isOverdue = true;
                overdueClaims.add(entry.getKey());
            } else {
                claim.payUpkeep();
            }
        }

        if (!overdueClaims.isEmpty()) {
            DiscordManager.sendAdminActionLog("⚠️ " + overdueClaims.size() + " claims are now overdue and unprotected!");
        }

        saveClaims(null);
    }

    // Persistence
    private static void loadClaims(MinecraftServer server) {
        if (CLAIMS_FILE.exists()) {
            try (FileReader reader = new FileReader(CLAIMS_FILE)) {
                Type type = new TypeToken<Map<String, Claim>>(){}.getType();
                Map<String, Claim> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    claims.putAll(loaded);

                    // Rebuild lookup maps
                    for (Map.Entry<String, Claim> entry : claims.entrySet()) {
                        Claim claim = entry.getValue();
                        playerClaims.computeIfAbsent(claim.ownerId, k -> new HashSet<>()).add(entry.getKey());

                        if (claim.type == ClaimType.CLAN && claim.clanName != null) {
                            clanClaims.computeIfAbsent(claim.clanName, k -> new HashSet<>()).add(entry.getKey());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load claims", e);
            }
        }
    }

    private static void saveClaims(MinecraftServer server) {
        try {
            CLAIMS_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CLAIMS_FILE)) {
                GSON.toJson(claims, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save claims", e);
        }
    }

    private static String getChunkKey(ChunkPos pos) {
        return pos.x + "," + pos.z;
    }
}
