package core.network;

import core.util.Safe;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;

public final class HandshakeServer {
    private HandshakeServer() {}

    public static void init() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) ->
            Safe.run("HandshakeServer.queryStart", () -> {
                // Receiver is per-connection.
                ServerLoginNetworking.registerReceiver(handler, CoreHandshake.CHANNEL, (srv, h, understood, buf, sync, responseSender) ->
                    Safe.run("HandshakeServer.receive", () -> handleResponse(h, understood, buf)));

                PacketByteBuf request = PacketByteBufs.create();
                request.writeVarInt(CoreHandshake.PROTOCOL);
                sender.sendPacket(CoreHandshake.CHANNEL, request);
            }));
    }

    private static void handleResponse(ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf) {
        if (!understood) {
            handler.disconnect(Text.literal("This server requires the Core mod installed."));
            return;
        }

        int protocol = Safe.call("HandshakeServer.readProtocol", buf::readVarInt, -1);
        if (protocol != CoreHandshake.PROTOCOL) {
            handler.disconnect(Text.literal("Core mod version mismatch (protocol " + protocol + ", expected " + CoreHandshake.PROTOCOL + ")."));
        }
    }
}

