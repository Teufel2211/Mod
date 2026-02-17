package core.moderation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModerationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DATA_FILE = new File("data/core-moderation.json");

    private static final Map<UUID, ModRecord> records = new ConcurrentHashMap<>();
    private static volatile MinecraftServer serverRef;

    private ModerationManager() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("ModerationManager.serverStarted", () -> {
            serverRef = server;
            load();
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("ModerationManager.serverStopping", ModerationManager::save));
    }

    public static ModRecord getOrCreate(UUID uuid) {
        if (uuid == null) return null;
        return records.computeIfAbsent(uuid, k -> new ModRecord());
    }

    public static ModRecord get(UUID uuid) {
        return uuid == null ? null : records.get(uuid);
    }

    public static void addNote(UUID target, UUID actor, String note) {
        if (target == null) return;
        ModRecord r = getOrCreate(target);
        r.notes.add(ModEntry.note(actor, note));
        trim(r);
        save();
    }

    public static void addWarn(UUID target, UUID actor, String reason) {
        if (target == null) return;
        ModRecord r = getOrCreate(target);
        r.warns.add(ModEntry.warn(actor, reason));
        trim(r);
        save();
    }

    public static void mute(UUID target, UUID actor, long durationMs, String reason) {
        if (target == null) return;
        ModRecord r = getOrCreate(target);
        long until = durationMs <= 0 ? 0 : (System.currentTimeMillis() + durationMs);
        r.mutedUntilMs = until;
        r.muteReason = reason == null ? "" : reason.trim();
        r.mutedByUuid = actor == null ? null : actor.toString();
        save();
    }

    public static void unmute(UUID target) {
        if (target == null) return;
        ModRecord r = getOrCreate(target);
        r.mutedUntilMs = -1;
        r.muteReason = "";
        r.mutedByUuid = null;
        save();
    }

    public static boolean isMuted(UUID target) {
        if (target == null) return false;
        ModRecord r = records.get(target);
        if (r == null) return false;
        if (r.mutedUntilMs == 0) return true; // permanent mute
        return r.mutedUntilMs > System.currentTimeMillis();
    }

    public static Map<UUID, ModRecord> snapshotAll() {
        return new HashMap<>(records);
    }

    private static void trim(ModRecord r) {
        if (r == null) return;
        if (r.notes.size() > 200) r.notes = new ArrayList<>(r.notes.subList(r.notes.size() - 200, r.notes.size()));
        if (r.warns.size() > 200) r.warns = new ArrayList<>(r.warns.subList(r.warns.size() - 200, r.warns.size()));
    }

    private static void load() {
        if (!DATA_FILE.exists()) return;
        try (FileReader reader = new FileReader(DATA_FILE)) {
            Type t = new TypeToken<Map<String, ModRecord>>(){}.getType();
            Map<String, ModRecord> loaded = GSON.fromJson(reader, t);
            if (loaded == null) return;
            for (var e : loaded.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    if (e.getValue() != null) records.put(uuid, e.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
            LOGGER.info("Loaded moderation records: {}", records.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load moderation data", e);
        }
    }

    public static void save() {
        try {
            DATA_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(DATA_FILE)) {
                Map<String, ModRecord> out = new HashMap<>();
                for (var e : records.entrySet()) out.put(e.getKey().toString(), e.getValue());
                GSON.toJson(out, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save moderation data", e);
        }
    }

    public static String resolveName(UUID uuid) {
        MinecraftServer server = serverRef;
        if (server == null || uuid == null) return uuid == null ? null : uuid.toString().substring(0, 8);
        var p = server.getPlayerManager().getPlayer(uuid);
        if (p != null) return p.getName().getString();
        return uuid.toString().substring(0, 8);
    }

    public static final class ModRecord {
        public List<ModEntry> notes = new ArrayList<>();
        public List<ModEntry> warns = new ArrayList<>();
        public long mutedUntilMs = -1; // -1 = not muted, 0 = permanent, >0 = until timestamp
        public String muteReason = "";
        public String mutedByUuid = null;
    }

    public static final class ModEntry {
        public long timestamp;
        public String type; // note | warn
        public String actorUuid;
        public String text;

        static ModEntry note(UUID actor, String note) {
            ModEntry e = new ModEntry();
            e.timestamp = System.currentTimeMillis();
            e.type = "note";
            e.actorUuid = actor == null ? null : actor.toString();
            e.text = note == null ? "" : note.trim();
            return e;
        }

        static ModEntry warn(UUID actor, String reason) {
            ModEntry e = new ModEntry();
            e.timestamp = System.currentTimeMillis();
            e.type = "warn";
            e.actorUuid = actor == null ? null : actor.toString();
            e.text = reason == null ? "" : reason.trim();
            return e;
        }
    }
}
