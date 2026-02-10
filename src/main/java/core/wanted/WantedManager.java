package core.wanted;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import core.util.Safe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import core.config.ConfigManager;

public class WantedManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File WANTED_FILE = new File("data/core-wanted.json");
    private static final Map<UUID, WantedEntry> wanted = new HashMap<>();

    public enum WantedReason {
        PLAYER_KILL,
        THEFT,
        TRESPASS,
        GRIEFING,
        CHEATING,
        ADMIN
    }

    public static final class WantedEntry {
        public UUID playerId;
        public int level; // 1..5
        public long expiresAt;
        public List<String> reasons = new ArrayList<>();

        public WantedEntry(UUID playerId, int level, long expiresAt, List<String> reasons) {
            this.playerId = playerId;
            this.level = clampLevel(level);
            this.expiresAt = expiresAt;
            if (reasons != null) this.reasons.addAll(reasons);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("WantedManager.loadWanted", () -> loadWanted(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("WantedManager.saveWanted", () -> saveWanted(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> Safe.run("WantedManager.tick", () -> tick(server)));
    }

    private static void tick(MinecraftServer server) {
        // Cleanup once per minute to keep overhead tiny.
        if (server.getTicks() % (20 * 60) != 0) return;
        boolean changed = wanted.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired());
        if (changed) saveWanted(null);
    }

    private static void loadWanted(MinecraftServer server) {
        if (WANTED_FILE.exists()) {
            try (FileReader reader = new FileReader(WANTED_FILE)) {
                // Backwards compatibility:
                // - old format: Map<String, Long> (expiry only)
                // - new format: Map<String, WantedEntry>
                String raw = readerToString(reader);
                if (raw == null || raw.isBlank()) return;

                if (raw.trim().startsWith("{") && raw.contains("\"expiresAt\"")) {
                    Type type = new TypeToken<Map<String, WantedEntry>>() {}.getType();
                    Map<String, WantedEntry> loaded = GSON.fromJson(raw, type);
                    if (loaded != null) {
                        for (Map.Entry<String, WantedEntry> entry : loaded.entrySet()) {
                            UUID id = UUID.fromString(entry.getKey());
                            WantedEntry we = entry.getValue();
                            if (we == null) continue;
                            we.playerId = id;
                            we.level = clampLevel(we.level);
                            wanted.put(id, we);
                        }
                    }
                } else {
                    Type type = new TypeToken<Map<String, Long>>() {}.getType();
                    Map<String, Long> temp = GSON.fromJson(raw, type);
                    if (temp != null) {
                        for (Map.Entry<String, Long> entry : temp.entrySet()) {
                            UUID id = UUID.fromString(entry.getKey());
                            long expiry = entry.getValue() == null ? 0L : entry.getValue();
                            wanted.put(id, new WantedEntry(id, 1, expiry, List.of("LEGACY")));
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load wanted", e);
            }
        }
    }

    private static void saveWanted(MinecraftServer server) {
        try {
            WANTED_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(WANTED_FILE)) {
                Map<String, WantedEntry> temp = new HashMap<>();
                for (Map.Entry<UUID, WantedEntry> entry : wanted.entrySet()) {
                    WantedEntry we = entry.getValue();
                    if (we == null) continue;
                    // Ensure key and payload line up.
                    we.playerId = entry.getKey();
                    we.level = clampLevel(we.level);
                    temp.put(entry.getKey().toString(), we);
                }
                GSON.toJson(temp, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save wanted", e);
        }
    }

    public static WantedEntry getEntry(UUID player) {
        WantedEntry entry = wanted.get(player);
        if (entry == null) return null;
        if (entry.isExpired()) {
            wanted.remove(player);
            return null;
        }
        return entry;
    }

    public static Map<UUID, WantedEntry> snapshot() {
        Map<UUID, WantedEntry> out = new HashMap<>();
        for (Map.Entry<UUID, WantedEntry> entry : wanted.entrySet()) {
            WantedEntry we = entry.getValue();
            if (we == null) continue;
            if (we.isExpired()) continue;
            out.put(entry.getKey(), we);
        }
        return out;
    }

    public static int getWantedLevel(UUID player) {
        WantedEntry entry = getEntry(player);
        return entry == null ? 0 : entry.level;
    }

    public static boolean isWanted(UUID player) {
        return getEntry(player) != null;
    }

    public static void setWanted(UUID player, int level, long durationMs, String reason) {
        long expiresAt = System.currentTimeMillis() + Math.max(0L, durationMs);
        List<String> reasons = new ArrayList<>();
        if (reason != null && !reason.isBlank()) reasons.add(reason);
        wanted.put(player, new WantedEntry(player, level, expiresAt, reasons));
        saveWanted(null);
    }

    public static void addWanted(UUID player, WantedReason reason, int levelDelta) {
        if (player == null) return;
        long durationMs = ConfigManager.getConfig() != null ? ConfigManager.getConfig().wanted.wantedDuration : 3600000L;
        addWanted(player, reason, levelDelta, durationMs);
    }

    public static void addWanted(UUID player, WantedReason reason, int levelDelta, long durationMs) {
        if (player == null) return;
        WantedEntry current = getEntry(player);
        long expiresAt = System.currentTimeMillis() + Math.max(0L, durationMs);
        String reasonString = reason == null ? "UNKNOWN" : reason.name();

        if (current == null) {
            wanted.put(player, new WantedEntry(player, Math.max(1, levelDelta), expiresAt, List.of(reasonString)));
        } else {
            current.level = clampLevel(current.level + levelDelta);
            current.expiresAt = Math.max(current.expiresAt, expiresAt);
            if (current.reasons == null) current.reasons = new ArrayList<>();
            current.reasons.add(reasonString);
        }
        saveWanted(null);
    }

    public static void removeWanted(UUID player) {
        if (wanted.remove(player) != null) saveWanted(null);
    }

    private static int clampLevel(int level) {
        if (level < 1) return 1;
        if (level > 5) return 5;
        return level;
    }

    private static String readerToString(FileReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int r;
        while ((r = reader.read(buf)) != -1) {
            sb.append(buf, 0, r);
        }
        return sb.toString();
    }
}
