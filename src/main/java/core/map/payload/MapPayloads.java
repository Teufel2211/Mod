package core.map.payload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MapPayloads {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private MapPayloads() {}

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) return;

        PayloadTypeRegistry.playC2S().register(OpenWorldMapPayload.ID, OpenWorldMapPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WaypointsPayload.ID, WaypointsPayload.CODEC);
    }
}

