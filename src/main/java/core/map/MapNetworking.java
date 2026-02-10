package core.map;

import java.util.Map;
import core.map.payload.MapPayloads;
import core.map.payload.OpenWorldMapPayload;
import core.map.payload.WaypointsPayload;
import core.util.Safe;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Networking utilities for map synchronization
 */
public class MapNetworking {
    public static void init() {
        MapPayloads.init();

        // Register player join event
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("MapNetworking.onJoin", () -> syncWaypointsToClient(handler.player)));

        ServerPlayNetworking.registerGlobalReceiver(OpenWorldMapPayload.ID, (payload, context) ->
            context.server().execute(() -> Safe.run("MapNetworking.openWorldMap", () -> WorldMapGui.open(context.player(), 0))));
    }

    /**
     * Synchronize waypoints visible to the player.
     */
    public static void syncWaypointsToClient(ServerPlayerEntity player) {
        Map<String, MapManager.Waypoint> visible = MapManager.getVisibleWaypoints(player);
        ServerPlayNetworking.send(player, WaypointsPayload.fromVisible(visible));
    }

    /**
     * Placeholder for claim overlay synchronization
     */
    public static void syncClaimOverlayToClient(net.minecraft.server.network.ServerPlayerEntity player, int centerX, int centerZ, int radius) {
        // No-op until a client-side listener is implemented.
    }

    /**
     * Placeholder for player markers synchronization
     */
    public static void syncPlayerMarkersToClient(net.minecraft.server.network.ServerPlayerEntity player) {
        // No-op until a client-side listener is implemented.
    }
}
