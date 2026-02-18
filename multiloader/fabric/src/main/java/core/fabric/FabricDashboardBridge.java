package core.fabric;

import core.common.service.DashboardService;
import core.common.service.DiscordService;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.List;

public final class FabricDashboardBridge {
    private static volatile boolean initialized;
    private static long lastUpdateMs;

    private FabricDashboardBridge() {}

    @SuppressWarnings("null")
    public static void init() {
        if (initialized) return;
        initialized = true;

        DashboardService.setControlBridge(command ->
            Safe.call("FabricDashboardBridge.command", () -> {
                if (serverRef == null || command == null || command.isBlank()) return false;
                String normalized = command.startsWith("/") ? command.substring(1) : command;
                serverRef.getCommandManager().getDispatcher().execute(normalized, serverRef.getCommandSource());
                return true;
            }, false));

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
            Safe.run("FabricDashboardBridge.started", () -> {
                serverRef = server;
                pushSnapshot(server);
            }));

        ServerTickEvents.END_SERVER_TICK.register(server ->
            Safe.run("FabricDashboardBridge.tick", () -> {
                long now = System.currentTimeMillis();
                if (now - lastUpdateMs < 2_000) return;
                lastUpdateMs = now;
                pushSnapshot(server);
            }));

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            Safe.run("FabricDashboardBridge.shutdown", () -> {
                serverRef = null;
                DashboardService.setControlBridge(null);
                DashboardService.shutdown();
                DiscordService.shutdown();
            }));
    }

    @SuppressWarnings("null")
    private static void pushSnapshot(net.minecraft.server.MinecraftServer server) {
        List<String> players = server.getPlayerManager().getPlayerList().stream()
            .map(p -> p.getName().getString())
            .toList();
        DashboardService.updateSnapshot(
            true,
            server.getServerMotd(),
            server.getVersion(),
            server.getMaxPlayerCount(),
            players
        );
    }

    private static volatile net.minecraft.server.MinecraftServer serverRef;
}
