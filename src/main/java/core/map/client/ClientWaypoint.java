package core.map.client;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ClientWaypoint {
    public final String name;
    public final BlockPos pos;
    public final String dimension;
    public final String type;
    public final String visibility;
    public final UUID owner;
    public final Set<UUID> sharedWith;

    public ClientWaypoint(String name, BlockPos pos, String dimension, String type, String visibility, UUID owner, Set<UUID> sharedWith) {
        this.name = name;
        this.pos = pos;
        this.dimension = dimension;
        this.type = type;
        this.visibility = visibility;
        this.owner = owner;
        this.sharedWith = (sharedWith != null) ? sharedWith : new HashSet<>();
    }
}

