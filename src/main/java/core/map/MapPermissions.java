package core.map;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import core.clans.ClanManager;

import java.util.UUID;

public class MapPermissions {

    public enum MapPermissionLevel {
        NONE,    // No access
        VIEW,    // Can view waypoints
        MODIFY_OWN,  // Can modify own waypoints
        MODIFY_TEAM, // Can modify team waypoints
        ADMIN    // Can modify all waypoints
    }

    /**
     * Check if a player can view a specific waypoint
     */
    public static boolean canViewWaypoint(ServerPlayerEntity player, MapManager.Waypoint waypoint) {
        if (player == null) return false;

        UUID playerId = player.getUuid();

        // Owner can always view their waypoints
        if (waypoint.owner.equals(playerId)) return true;

        // Check visibility settings
        switch (waypoint.visibility.toLowerCase()) {
            case "global":
                return true;
            case "team":
                return isSameTeam(playerId, waypoint.owner);
            case "private":
            default:
                return waypoint.sharedWith != null && waypoint.sharedWith.contains(playerId);
        }
    }

    /**
     * Check if a player can modify a specific waypoint
     */
    public static boolean canModifyWaypoint(ServerPlayerEntity player, MapManager.Waypoint waypoint) {
        if (player == null) return false;

        UUID playerId = player.getUuid();

        // Owner can always modify their waypoints
        if (waypoint.owner.equals(playerId)) return true;

        // Check team permissions
        if (waypoint.visibility.equalsIgnoreCase("team") && isSameTeam(playerId, waypoint.owner)) {
            return true;
        }

        // Check admin permissions
        return hasAdminPermission(player);
    }

    /**
     * Check if a player has admin permissions for map management
     */
    public static boolean hasAdminPermission(ServerPlayerEntity player) {
        if (player == null) return false;

        return player.getCommandSource().getPermissions()
            .hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    /**
     * Check if two players are in the same team/clan
     */
    private static boolean isSameTeam(UUID player1, UUID player2) {
        String clan1 = ClanManager.getPlayerClanName(player1);
        String clan2 = ClanManager.getPlayerClanName(player2);
        return clan1 != null && clan1.equalsIgnoreCase(clan2);
    }

    /**
     * Get the permission level for a player
     */
    public static MapPermissionLevel getPermissionLevel(ServerPlayerEntity player) {
        if (hasAdminPermission(player)) return MapPermissionLevel.ADMIN;
        // Basic granularity: team members can modify team waypoints, others only their own.
        // The calling code still enforces ownership for actual modifications.
        if (player != null) {
            return MapPermissionLevel.MODIFY_TEAM;
        }
        return MapPermissionLevel.MODIFY_OWN;
    }
}
