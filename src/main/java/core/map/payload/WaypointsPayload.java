package core.map.payload;

import core.map.MapManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record WaypointsPayload(List<Entry> entries) implements CustomPayload {
    public static final Id<WaypointsPayload> ID = CustomPayload.id("core:map_waypoints");
    public static final PacketCodec<PacketByteBuf, WaypointsPayload> CODEC = CustomPayload.codecOf(WaypointsPayload::encode, WaypointsPayload::decode);

    public record Entry(
        String name,
        BlockPos pos,
        String dimension,
        String type,
        String visibility,
        UUID owner,
        List<UUID> sharedWith
    ) {}

    public static WaypointsPayload fromVisible(Map<String, MapManager.Waypoint> visible) {
        List<Entry> out = new ArrayList<>(visible.size());
        for (Map.Entry<String, MapManager.Waypoint> entry : visible.entrySet()) {
            String name = entry.getKey();
            MapManager.Waypoint wp = entry.getValue();
            List<UUID> shared = new ArrayList<>();
            if (wp.sharedWith != null) shared.addAll(wp.sharedWith);
            out.add(new Entry(
                name,
                wp.pos,
                wp.dimension != null ? wp.dimension : "",
                wp.type != null ? wp.type : "",
                wp.visibility != null ? wp.visibility : "",
                wp.owner,
                shared
            ));
        }
        return new WaypointsPayload(out);
    }

    private static void encode(WaypointsPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.entries.size());
        for (Entry e : payload.entries) {
            buf.writeString(e.name);
            buf.writeInt(e.pos.getX());
            buf.writeInt(e.pos.getY());
            buf.writeInt(e.pos.getZ());
            buf.writeString(e.dimension);
            buf.writeString(e.type);
            buf.writeString(e.visibility);
            buf.writeUuid(e.owner);
            buf.writeVarInt(e.sharedWith != null ? e.sharedWith.size() : 0);
            if (e.sharedWith != null) {
                for (UUID uuid : e.sharedWith) {
                    buf.writeUuid(uuid);
                }
            }
        }
    }

    private static WaypointsPayload decode(PacketByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            String name = buf.readString(32767);
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            String dim = buf.readString(32767);
            String type = buf.readString(32767);
            String visibility = buf.readString(32767);
            UUID owner = buf.readUuid();
            int sharedCount = buf.readVarInt();
            List<UUID> sharedWith = new ArrayList<>(sharedCount);
            for (int s = 0; s < sharedCount; s++) {
                sharedWith.add(buf.readUuid());
            }
            out.add(new Entry(name, new BlockPos(x, y, z), dim, type, visibility, owner, sharedWith));
        }
        return new WaypointsPayload(out);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

