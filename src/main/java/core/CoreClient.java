package core;

import core.network.CoreHandshake;
import core.map.client.MapClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;

import java.util.concurrent.CompletableFuture;

public final class CoreClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Respond during login so the server can require this mod.
        ClientLoginNetworking.registerGlobalReceiver(CoreHandshake.CHANNEL, (client, handler, buf, callbacksConsumer) ->
            CompletableFuture.completedFuture(PacketByteBufs.create().writeVarInt(CoreHandshake.PROTOCOL)));

        MapClient.init();
    }
}
