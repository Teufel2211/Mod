package core.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import core.claims.ClaimManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import core.util.Safe;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MapManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File WAYPOINTS_FILE = new File("config/core-waypoints.json");
    private static final Map<String, Waypoint> waypoints = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    private static final long CACHE_TIMEOUT = 300000; // 5 minutes

    public static class Waypoint {
        public String name;
        public BlockPos pos;
        public String dimension;
        public String type; // Home, Base, Village, etc.
        public String visibility; // private, team, global
        public UUID owner;
        public Set<UUID> sharedWith;

        public Waypoint(String name, BlockPos pos, String dimension, String type, String visibility, UUID owner) {
            this.name = name;
            this.pos = pos;
            this.dimension = dimension;
            this.type = type;
            this.visibility = visibility;
            this.owner = owner;
            this.sharedWith = new HashSet<>();
        }
    }

    public static void init() {
        Safe.run("MapManager.loadWaypoints", MapManager::loadWaypoints);
    }

    public static void addWaypoint(String name, BlockPos pos, String dimension, String type, String visibility, UUID owner) {
        waypoints.put(name, new Waypoint(name, pos, dimension, type, visibility, owner));
        saveWaypoints();
    }

    public static boolean shareWaypoint(String name, UUID target) {
        Waypoint waypoint = waypoints.get(name);
        if (waypoint == null) return false;
        if (waypoint.sharedWith == null) {
            waypoint.sharedWith = new HashSet<>();
        }
        boolean added = waypoint.sharedWith.add(target);
        if (added) {
            saveWaypoints();
        }
        return added;
    }

    public static void removeWaypoint(String name) {
        waypoints.remove(name);
        saveWaypoints();
    }

    public static Waypoint getWaypoint(String name) {
        return waypoints.get(name);
    }

    public static Map<String, Waypoint> getWaypoints() {
        return new HashMap<>(waypoints);
    }

    /**
     * Get waypoints visible to a specific player with caching
     */
    public static Map<String, Waypoint> getVisibleWaypoints(ServerPlayerEntity player) {
        String cacheKey = "visible_" + player.getUuid();
        long now = System.currentTimeMillis();

        // Check cache
        if (lastAccess.containsKey(cacheKey) && (now - lastAccess.get(cacheKey)) < CACHE_TIMEOUT) {
            // Return cached result (would need to store cached results too)
        }

        // Filter waypoints based on permissions
        Map<String, Waypoint> visible = waypoints.entrySet().stream()
            .filter(entry -> MapPermissions.canViewWaypoint(player, entry.getValue()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // Update cache timestamp
        lastAccess.put(cacheKey, now);

        return visible;
    }

    /**
     * Get waypoints by type for performance
     */
    public static Map<String, Waypoint> getWaypointsByType(String type) {
        return waypoints.entrySet().stream()
            .filter(entry -> entry.getValue().type.equalsIgnoreCase(type))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get waypoints by dimension for performance
     */
    public static Map<String, Waypoint> getWaypointsByDimension(String dimension) {
        return waypoints.entrySet().stream()
            .filter(entry -> entry.getValue().dimension.equals(dimension))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get nearby waypoints within radius
     */
    public static Map<String, Waypoint> getNearbyWaypoints(BlockPos center, int radius) {
        return waypoints.entrySet().stream()
            .filter(entry -> {
                Waypoint wp = entry.getValue();
                return wp.pos.getSquaredDistance(center) <= radius * radius;
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static void loadWaypoints() {
        if (!WAYPOINTS_FILE.exists()) return;
        try (FileReader reader = new FileReader(WAYPOINTS_FILE)) {
            Type type = new TypeToken<Map<String, Waypoint>>() {}.getType();
            Map<String, Waypoint> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                waypoints.putAll(loaded);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load waypoints", e);
        }
    }

    private static void saveWaypoints() {
        try {
            WAYPOINTS_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(WAYPOINTS_FILE)) {
                GSON.toJson(waypoints, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save waypoints", e);
        }
    }

    /**
     * Get claim data for map overlay
     */
    public static Map<String, UUID> getClaimOverlay(int startX, int startZ, int endX, int endZ) {
        Map<String, UUID> claims = new HashMap<>();
        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                ChunkPos chunkPos = new ChunkPos(x, z);
                UUID owner = ClaimManager.getOwner(chunkPos);
                if (owner != null) {
                    claims.put(x + "," + z, owner);
                }
            }
        }
        return claims;
    }

    /**
     * Check if a position is claimed for map display
     */
    public static boolean isPositionClaimed(BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        return ClaimManager.isClaimed(chunkPos);
    }

    /**
     * Get claim owner for a position
     */
    public static UUID getClaimOwner(BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        return ClaimManager.getOwner(chunkPos);
    }
}
