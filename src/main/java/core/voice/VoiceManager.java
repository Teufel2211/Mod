package core.voice;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import core.util.Safe;
// import de.maxhenkel.voicechat.api.VoicechatServerApi;
// import de.maxhenkel.voicechat.api.VoicechatServerApiProvider;
// import de.maxhenkel.voicechat.api.VoicechatGroup;
// import java.util.UUID;

public class VoiceManager {

    // private static VoicechatServerApi api;

    public static void init() {
        // api = VoicechatServerApiProvider.get();
        // Register events for Voice Chat integration
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("VoiceManager.onServerStarted", () -> onServerStarted(server)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("VoiceManager.onJoin", () -> onPlayerJoin(handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            Safe.run("VoiceManager.onDisconnect", () -> onPlayerLeave(handler.player)));

        // Note: Actual Voice Chat API integration requires the mod to be present at runtime
        // The code below is commented out until the dependency is resolved
        /*
        VoicechatServerApi api = VoicechatServerApiProvider.get();
        if (api != null) {
            // Register group events
            // Events would be registered here
        }
        */
    }

    private static void onServerStarted(MinecraftServer server) {
        // Initialize voice chat server if available
    }

    private static void onPlayerJoin(ServerPlayerEntity player) {
        // Handle player joining voice chat
    }

    private static void onPlayerLeave(ServerPlayerEntity player) {
        // Handle player leaving voice chat
    }

    // Placeholder for group events - would be implemented with Voice Chat API

    public static void pushPlayer(ServerPlayerEntity player, String groupNameOrId) {
        // if (api == null) return;
        // VoicechatGroup group = null;
        // try {
        //     UUID groupId = UUID.fromString(groupNameOrId);
        //     group = api.getGroup(groupId);
        // } catch (IllegalArgumentException e) {
        //     group = api.getGroup(groupNameOrId);
        // }
        // if (group != null) {
        //     group.addPlayer(player.getUuid());
        // }
    }

    public static void releasePlayer(ServerPlayerEntity player) {
        // if (api == null) return;
        // api.getPlayerState(player.getUuid()).ifPresent(state -> state.setGroup(null));
    }
}
