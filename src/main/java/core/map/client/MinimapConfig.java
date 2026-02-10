package core.map.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MinimapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("core-minimap.json");

    public boolean enabled = true;
    public boolean rotate = false;
    public boolean circle = false;
    public int sizePx = 96;
    public int blocksPerPixel = 2;
    public int updateColumnsPerTick = 4;
    public boolean showInfo = true;
    public boolean showWaypoints = true;

    private static MinimapConfig INSTANCE = new MinimapConfig();

    private MinimapConfig() {}

    public static MinimapConfig get() {
        return INSTANCE;
    }

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE)) {
            MinimapConfig loaded = GSON.fromJson(reader, MinimapConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
                clamp();
            }
        } catch (Exception ignored) {
            // Keep defaults if config is malformed.
        }
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static void clamp() {
        INSTANCE.sizePx = Math.max(48, Math.min(160, INSTANCE.sizePx));
        INSTANCE.blocksPerPixel = Math.max(1, Math.min(16, INSTANCE.blocksPerPixel));
        INSTANCE.updateColumnsPerTick = Math.max(1, Math.min(32, INSTANCE.updateColumnsPerTick));
    }

    private static void toast(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), true);
        }
    }

    public static void toggleEnabled() {
        INSTANCE.enabled = !INSTANCE.enabled;
        save();
        toast("Minimap: " + (INSTANCE.enabled ? "ON" : "OFF"));
    }

    public static void zoomIn() {
        int next = Math.max(1, INSTANCE.blocksPerPixel / 2);
        if (next != INSTANCE.blocksPerPixel) {
            INSTANCE.blocksPerPixel = next;
            save();
            toast("Minimap zoom: " + INSTANCE.blocksPerPixel + " blocks/px");
        }
    }

    public static void zoomOut() {
        int next = Math.min(16, INSTANCE.blocksPerPixel * 2);
        if (next != INSTANCE.blocksPerPixel) {
            INSTANCE.blocksPerPixel = next;
            save();
            toast("Minimap zoom: " + INSTANCE.blocksPerPixel + " blocks/px");
        }
    }

    public static void toggleRotate() {
        INSTANCE.rotate = !INSTANCE.rotate;
        save();
        toast("Minimap rotation: " + (INSTANCE.rotate ? "ON" : "OFF"));
    }

    public static void toggleCircle() {
        INSTANCE.circle = !INSTANCE.circle;
        save();
        toast("Minimap shape: " + (INSTANCE.circle ? "CIRCLE" : "SQUARE"));
    }
}

