package core.map.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record OpenWorldMapPayload() implements CustomPayload {
    public static final Id<OpenWorldMapPayload> ID = CustomPayload.id("core:open_world_map");
    public static final PacketCodec<PacketByteBuf, OpenWorldMapPayload> CODEC = PacketCodec.unit(new OpenWorldMapPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

