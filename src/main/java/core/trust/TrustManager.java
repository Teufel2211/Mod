package core.trust;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import core.util.Safe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TrustManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File TRUST_FILE = new File("data/core-trust.json");
    private static final Map<String, Set<UUID>> trusts = new HashMap<>(); // Chunk key to trusted players

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("TrustManager.loadTrusts", () -> loadTrusts(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("TrustManager.saveTrusts", () -> saveTrusts(server)));
    }

    private static void loadTrusts(MinecraftServer server) {
        if (TRUST_FILE.exists()) {
            try (FileReader reader = new FileReader(TRUST_FILE)) {
                Type type = new TypeToken<Map<String, Set<String>>>(){}.getType();
                Map<String, Set<String>> temp = GSON.fromJson(reader, type);
                for (Map.Entry<String, Set<String>> entry : temp.entrySet()) {
                    Set<UUID> uuids = new HashSet<>();
                    for (String s : entry.getValue()) {
                        uuids.add(UUID.fromString(s));
                    }
                    trusts.put(entry.getKey(), uuids);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load trusts", e);
            }
        }
    }

    private static void saveTrusts(MinecraftServer server) {
        try {
            TRUST_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(TRUST_FILE)) {
                Map<String, Set<String>> temp = new HashMap<>();
                for (Map.Entry<String, Set<UUID>> entry : trusts.entrySet()) {
                    Set<String> strings = new HashSet<>();
                    for (UUID uuid : entry.getValue()) {
                        strings.add(uuid.toString());
                    }
                    temp.put(entry.getKey(), strings);
                }
                GSON.toJson(temp, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save trusts", e);
        }
    }

    public static boolean trustPlayer(UUID owner, ChunkPos pos, UUID trusted) {
        String key = pos.x + "," + pos.z;
        if (!core.claims.ClaimManager.getOwner(pos).equals(owner)) return false;
        trusts.computeIfAbsent(key, k -> new HashSet<>()).add(trusted);
        return true;
    }

    public static boolean untrustPlayer(UUID owner, ChunkPos pos, UUID trusted) {
        String key = pos.x + "," + pos.z;
        if (!core.claims.ClaimManager.getOwner(pos).equals(owner)) return false;
        Set<UUID> set = trusts.get(key);
        if (set != null) {
            set.remove(trusted);
        }
        return true;
    }

    public static boolean isTrusted(ChunkPos pos, UUID player) {
        String key = pos.x + "," + pos.z;
        Set<UUID> set = trusts.get(key);
        return set != null && set.contains(player);
    }
}
