package core.map;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MapSecurity {

    private static final Map<UUID, Long> lastWaypointAction = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> actionCount = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_WINDOW = 60000; // 1 minute
    private static final int MAX_ACTIONS_PER_WINDOW = 10;

    /**
     * Validate waypoint name for security
     */
    public static boolean isValidWaypointName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        if (name.length() > 50) return false; // Prevent extremely long names

        // Allow only alphanumeric characters, spaces, underscores, and hyphens
        return name.matches("[a-zA-Z0-9 _\\-]+");
    }

    /**
     * Validate waypoint type
     */
    public static boolean isValidWaypointType(String type) {
        if (type == null || type.trim().isEmpty()) return false;
        String[] validTypes = {"home", "base", "village", "mine", "farm", "shop", "portal", "temple", "fortress", "other"};
        for (String validType : validTypes) {
            if (validType.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    /**
     * Validate waypoint visibility
     */
    public static boolean isValidVisibility(String visibility) {
        if (visibility == null) return false;
        return visibility.equalsIgnoreCase("private") ||
               visibility.equalsIgnoreCase("team") ||
               visibility.equalsIgnoreCase("global");
    }

    /**
     * Check rate limiting for waypoint actions
     */
    public static boolean checkRateLimit(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        long now = System.currentTimeMillis();

        // Clean up old entries
        lastWaypointAction.entrySet().removeIf(entry ->
            (now - entry.getValue()) > RATE_LIMIT_WINDOW * 2);

        Long lastAction = lastWaypointAction.get(playerId);
        if (lastAction == null) {
            lastWaypointAction.put(playerId, now);
            actionCount.put(playerId, 1);
            return true;
        }

        if ((now - lastAction) > RATE_LIMIT_WINDOW) {
            // Reset window
            lastWaypointAction.put(playerId, now);
            actionCount.put(playerId, 1);
            return true;
        }

        int count = actionCount.getOrDefault(playerId, 0) + 1;
        if (count > MAX_ACTIONS_PER_WINDOW) {
            return false; // Rate limited
        }

        actionCount.put(playerId, count);
        return true;
    }

    /**
     * Sanitize input strings to prevent injection
     */
    public static String sanitizeInput(String input) {
        if (input == null) return null;
        // Remove any potentially dangerous characters
        return input.replaceAll("[<>\"'&]", "").trim();
    }

    /**
     * Check if position is within reasonable bounds
     */
    public static boolean isValidPosition(BlockPos pos) {
        // Prevent waypoints in unreasonable locations
        return pos.getX() >= -30000000 && pos.getX() <= 30000000 &&
               pos.getY() >= -64 && pos.getY() <= 320 &&
               pos.getZ() >= -30000000 && pos.getZ() <= 30000000;
    }
}