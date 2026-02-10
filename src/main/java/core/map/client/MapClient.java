package core.map.client;

import core.map.payload.MapPayloads;
import core.map.payload.OpenWorldMapPayload;
import core.map.payload.WaypointsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MapClient {
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("core", "core"));

    private static KeyBinding keyToggleMinimap;
    private static KeyBinding keyZoomIn;
    private static KeyBinding keyZoomOut;
    private static KeyBinding keyToggleRotate;
    private static KeyBinding keyToggleShape;
    private static KeyBinding keyOpenWorldMap;

    private MapClient() {}

    public static void init() {
        MapPayloads.init();
        MinimapConfig.load();

        keyToggleMinimap = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.core.minimap.toggle",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_M,
            KEY_CATEGORY
        ));
        keyZoomIn = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.core.minimap.zoom_in",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_EQUAL,
            KEY_CATEGORY
        ));
        keyZoomOut = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.core.minimap.zoom_out",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_MINUS,
            KEY_CATEGORY
        ));
        keyToggleRotate = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.core.minimap.rotate",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_R,
            KEY_CATEGORY
        ));
        keyToggleShape = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.core.minimap.shape",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_C,
            KEY_CATEGORY
        ));
        keyOpenWorldMap = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.core.worldmap.open",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_J,
            KEY_CATEGORY
        ));

        ClientPlayNetworking.registerGlobalReceiver(WaypointsPayload.ID, (payload, context) -> {
            Map<String, ClientWaypoint> out = new HashMap<>(Math.max(16, payload.entries().size()));
            for (WaypointsPayload.Entry e : payload.entries()) {
                Set<java.util.UUID> sharedWith = new HashSet<>();
                if (e.sharedWith() != null) sharedWith.addAll(e.sharedWith());
                out.put(e.name(), new ClientWaypoint(e.name(), e.pos(), e.dimension(), e.type(), e.visibility(), e.owner(), sharedWith));
            }
            context.client().execute(() -> ClientWaypoints.replaceAll(out));
        });

        MinimapHud minimapHud = new MinimapHud();
        HudRenderCallback.EVENT.register(minimapHud);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (keyToggleMinimap.wasPressed()) {
                MinimapConfig.toggleEnabled();
            }
            while (keyZoomIn.wasPressed()) {
                MinimapConfig.zoomIn();
            }
            while (keyZoomOut.wasPressed()) {
                MinimapConfig.zoomOut();
            }
            while (keyToggleRotate.wasPressed()) {
                MinimapConfig.toggleRotate();
            }
            while (keyToggleShape.wasPressed()) {
                MinimapConfig.toggleCircle();
            }
            while (keyOpenWorldMap.wasPressed()) {
                ClientPlayNetworking.send(new OpenWorldMapPayload());
            }

            minimapHud.tick(client);
        });
    }
}
