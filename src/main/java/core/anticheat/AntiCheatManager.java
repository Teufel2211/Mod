package core.anticheat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import core.util.Safe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import core.bounty.BountyManager;
import core.config.ConfigManager;
import core.discord.DiscordManager;
import core.wanted.WantedManager;
import core.wanted.WantedManager.WantedReason;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.ActionResult;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AntiCheatManager {
    private static final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> frozenPlayers = new ConcurrentHashMap<>();
    private static final Map<String, Long> ipRateLimits = new ConcurrentHashMap<>();
    private static volatile MinecraftServer serverRef;
    // Code-level ignore list (UUIDs). Add UUIDs here if you want hard-coded exemptions.
    private static final Set<UUID> HARD_IGNORED_PLAYERS = Set.of();

    public enum CheckType {
        MOVEMENT_SPEED, MOVEMENT_FLY, MOVEMENT_ELYTRA, MOVEMENT_NO_SLOW,
        COMBAT_KILLAURA, COMBAT_REACH, COMBAT_AUTOCLICK, COMBAT_CRITICALS,
        WORLD_NUKER, WORLD_SCAFFOLD, WORLD_FASTBREAK,
        INVENTORY_AUTOSTEAL, INVENTORY_QUICKDROP
    }

    public static class PlayerData {
        public UUID playerId;
        public Vec3d lastPosition;
        public Vec3d lastVelocity;
        public long lastMoveTime;
        public long lastAttackTime;
        public long lastBlockBreakTime;
        public int consecutiveMoves;
        public int lastTickAge;
        public double violationLevel;
        public Map<CheckType, Double> checkViolations;
        public List<String> recentViolations;
        public Deque<Long> recentAttackTimes;
        public Deque<Long> recentBreakTimes;

        public PlayerData(UUID playerId) {
            this.playerId = playerId;
            this.checkViolations = new HashMap<>();
            this.recentViolations = new ArrayList<>();
            this.violationLevel = 0.0;
            this.recentAttackTimes = new ArrayDeque<>();
            this.recentBreakTimes = new ArrayDeque<>();
        }

        public void addViolation(CheckType type, double severity, String details) {
            checkViolations.merge(type, severity, Double::sum);
            violationLevel += severity;
            recentViolations.add(type + ": " + details);

            // Keep only recent violations
            if (recentViolations.size() > 10) {
                recentViolations.remove(0);
            }

            // Handle violation consequences
            handleViolation(type, severity, details);
        }

        public String getViolationLevels() {
            StringBuilder sb = new StringBuilder();
            sb.append("Total: ").append(String.format("%.2f", violationLevel));
            for (Map.Entry<CheckType, Double> entry : checkViolations.entrySet()) {
                sb.append(", ").append(entry.getKey()).append(": ").append(String.format("%.2f", entry.getValue()));
            }
            return sb.toString();
        }

        private void handleViolation(CheckType type, double severity, String details) {
            boolean critical = severity >= ConfigManager.getConfig().antiCheat.criticalThreshold;

            if (ConfigManager.getConfig().antiCheat.enableDiscordAlerts) {
                DiscordManager.sendAnticheatAlert(
                    "Player: <@" + DiscordManager.getDiscordId(playerId) + ">\n" +
                        "Check: " + type + "\n" +
                        "Severity: " + String.format("%.2f", severity) + "\n" +
                        "Details: " + details + "\n" +
                        "Total VL: " + String.format("%.2f", violationLevel),
                    critical
                );
            }

            // Apply consequences
            if (violationLevel >= ConfigManager.getConfig().antiCheat.freezeThreshold) {
                freezePlayer(playerId);
            }

            if (violationLevel >= ConfigManager.getConfig().antiCheat.kickThreshold) {
                kickPlayer(playerId, "Cheating detected");
                if (ConfigManager.getConfig().antiCheat.enableDiscordAlerts) {
                    DiscordManager.sendAnticheatAlert("Player <@" + DiscordManager.getDiscordId(playerId) + "> kicked for cheating!", true);
                }
            }

            if (violationLevel >= ConfigManager.getConfig().antiCheat.banThreshold) {
                banPlayer(playerId, "Cheating detected");
                if (ConfigManager.getConfig().antiCheat.enableDiscordAlerts) {
                    DiscordManager.sendAnticheatAlert("Player <@" + DiscordManager.getDiscordId(playerId) + "> banned for cheating!", true);
                }
            }

            // Make wanted for cheating
            if (critical) {
                WantedManager.addWanted(playerId, WantedReason.CHEATING, 1);
            }
        }
    }

    public static PlayerData getViolations(UUID playerId) {
        return playerData.get(playerId);
    }

    public static boolean isIgnored(UUID playerId) {
        if (playerId == null) return false;
        if (HARD_IGNORED_PLAYERS.contains(playerId)) return true;
        if (ConfigManager.getConfig() == null || ConfigManager.getConfig().antiCheat == null) return false;
        List<String> ignored = ConfigManager.getConfig().antiCheat.ignoredPlayers;
        if (ignored == null || ignored.isEmpty()) return false;
        return ignored.contains(playerId.toString());
    }

    private static boolean isAdmin(ServerPlayerEntity player) {
        if (player == null) return false;
        return player.getCommandSource().getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    private static boolean shouldIgnore(ServerPlayerEntity player) {
        if (player == null) return true;
        if (player.isCreative() || player.isSpectator()) return true;
        if (isIgnored(player.getUuid())) return true;
        if (ConfigManager.getConfig() != null && ConfigManager.getConfig().antiCheat != null && ConfigManager.getConfig().antiCheat.ignoreAdmins) {
            return isAdmin(player);
        }
        return false;
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("AntiCheatManager.onServerStart", () -> onServerStart(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> Safe.run("AntiCheatManager.onServerTick", () -> onServerTick(server)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("AntiCheatManager.onJoin", () -> playerData.put(handler.player.getUuid(), new PlayerData(handler.player.getUuid()))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            Safe.run("AntiCheatManager.onDisconnect", () -> playerData.remove(handler.player.getUuid())));

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (ConfigManager.getConfig() == null || ConfigManager.getConfig().antiCheat == null) return ActionResult.PASS;
            if (!ConfigManager.getConfig().antiCheat.enableAntiCheat || !ConfigManager.getConfig().antiCheat.enableCombatChecks) return ActionResult.PASS;
            if (shouldIgnore(serverPlayer)) return ActionResult.PASS;
            Safe.run("AntiCheatManager.onAttack", () -> onAttack(serverPlayer, entity));
            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) return;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
            if (ConfigManager.getConfig() == null || ConfigManager.getConfig().antiCheat == null) return;
            if (!ConfigManager.getConfig().antiCheat.enableAntiCheat || !ConfigManager.getConfig().antiCheat.enableWorldChecks) return;
            if (shouldIgnore(serverPlayer)) return;
            Safe.run("AntiCheatManager.onBlockBreak", () -> onBlockBreak(serverPlayer, pos));
        });
    }

    private static void onServerStart(MinecraftServer server) {
        serverRef = server;
    }

    private static void onServerTick(MinecraftServer server) {
        // Run checks every tick but be performance-conscious
        long currentTime = System.currentTimeMillis();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Safe.run("AntiCheatManager.tickPlayer", () -> tickPlayer(player, currentTime));
        }

        // Clean up old rate limit data
        ipRateLimits.entrySet().removeIf(entry ->
            currentTime - entry.getValue() > 60000); // Remove entries older than 1 minute
    }

    private static void tickPlayer(ServerPlayerEntity player, long currentTime) {
        if (ConfigManager.getConfig() == null || ConfigManager.getConfig().antiCheat == null) return;
        UUID playerId = player.getUuid();
        PlayerData data = playerData.get(playerId);

        if (data == null) return;
        if (!ConfigManager.getConfig().antiCheat.enableAntiCheat) return;
        if (shouldIgnore(player)) return;

        // Skip frozen players
        if (frozenPlayers.containsKey(playerId)) {
            // Check if freeze should be lifted
            if (currentTime - frozenPlayers.get(playerId) > ConfigManager.getConfig().antiCheat.freezeDurationMs) {
                unfreezePlayer(playerId);
            } else {
                // Keep player frozen - reset position
                if (data.lastPosition != null) {
                    player.teleport(player.getEntityWorld(), data.lastPosition.x, data.lastPosition.y, data.lastPosition.z, Set.<PositionFlag>of(), 0F, 0F, false);
                }
                return;
            }
        }

        // Run checks
        runMovementChecks(player, data);
        runCombatChecks(player, data);
        runWorldChecks(player, data);

        // Update position data
        data.lastPosition = player.getEntityPos();
        data.lastVelocity = player.getVelocity();
        data.lastMoveTime = currentTime;
        data.lastTickAge = player.age;
    }

    private static void onAttack(ServerPlayerEntity player, Entity target) {
        PlayerData data = playerData.get(player.getUuid());
        if (data == null || target == null) return;

        long now = System.currentTimeMillis();
        data.lastAttackTime = now;
        data.recentAttackTimes.addLast(now);
        while (!data.recentAttackTimes.isEmpty() && now - data.recentAttackTimes.peekFirst() > 1000L) {
            data.recentAttackTimes.removeFirst();
        }

        int cps = data.recentAttackTimes.size();
        int maxCps = ConfigManager.getConfig().antiCheat.maxClicksPerSecond;
        if (maxCps > 0 && cps > maxCps) {
            double severity = 1.0 + Math.min(4.0, (cps - maxCps) * 0.25);
            data.addViolation(CheckType.COMBAT_AUTOCLICK, severity, "CPS: " + cps);
        }

        double maxReach = ConfigManager.getConfig().antiCheat.maxReachBlocks;
        if (maxReach > 0.0) {
            double reachSq = squaredDistanceToBox(target.getBoundingBox(), player.getEyePos());
            double maxSq = maxReach * maxReach;
            if (reachSq > maxSq) {
                double reach = Math.sqrt(reachSq);
                double severity = 1.5 + Math.min(4.0, (reach - maxReach));
                data.addViolation(CheckType.COMBAT_REACH, severity, "Reach: " + String.format("%.2f", reach));
            }
        }
    }

    private static double squaredDistanceToBox(Box box, Vec3d point) {
        // Clamp point to AABB then compute squared distance.
        double x = clamp(point.x, box.minX, box.maxX);
        double y = clamp(point.y, box.minY, box.maxY);
        double z = clamp(point.z, box.minZ, box.maxZ);
        double dx = point.x - x;
        double dy = point.y - y;
        double dz = point.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static void onBlockBreak(ServerPlayerEntity player, BlockPos pos) {
        PlayerData data = playerData.get(player.getUuid());
        if (data == null) return;

        long now = System.currentTimeMillis();
        if (data.lastBlockBreakTime > 0) {
            long dt = now - data.lastBlockBreakTime;
            long minInterval = ConfigManager.getConfig().antiCheat.minBlockBreakIntervalMs;
            if (minInterval > 0 && dt < minInterval) {
                double severity = 1.0 + Math.min(4.0, (minInterval - dt) / 25.0);
                data.addViolation(CheckType.WORLD_FASTBREAK, severity, "Interval: " + dt + "ms");
            }
        }
        data.lastBlockBreakTime = now;

        data.recentBreakTimes.addLast(now);
        while (!data.recentBreakTimes.isEmpty() && now - data.recentBreakTimes.peekFirst() > 1000L) {
            data.recentBreakTimes.removeFirst();
        }
        int bps = data.recentBreakTimes.size();
        int maxBps = ConfigManager.getConfig().antiCheat.maxBlockBreaksPerSecond;
        if (maxBps > 0 && bps > maxBps) {
            double severity = 1.0 + Math.min(6.0, (bps - maxBps) * 0.25);
            data.addViolation(CheckType.WORLD_NUKER, severity, "Breaks/s: " + bps + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
    }

    // Movement Checks
    private static void runMovementChecks(ServerPlayerEntity player, PlayerData data) {
        if (!ConfigManager.getConfig().antiCheat.enableMovementChecks) return;
        if (player.hasVehicle()) return;

        Vec3d currentPos = player.getEntityPos();
        Vec3d velocity = player.getVelocity();

        // Speed check
        if (data.lastPosition != null) {
            double distance = currentPos.distanceTo(data.lastPosition);
            int ticks = Math.max(1, player.age - data.lastTickAge);

            // Teleports / big corrections: ignore this sample
            if (distance > 8.0) {
                return;
            }

            double base = player.isSprinting() ? 0.13 : 0.10;
            base *= player.isOnGround() ? 1.0 : 1.2; // Allow slightly higher speed when jumping

            if (player.hasStatusEffect(StatusEffects.SPEED)) {
                base *= 1.5;
            }
            if (player.hasStatusEffect(StatusEffects.SLOWNESS)) {
                base *= 0.8;
            }

            double perTick = distance / ticks;

            if (perTick > base * 2.0) { // Allow some tolerance
                data.addViolation(CheckType.MOVEMENT_SPEED, 1.0,
                    "Moved " + String.format("%.2f", distance) + " blocks in " + ticks + "t (" + String.format("%.2f", perTick) + "/t)");
            }
        }

        // Fly check
        if (!player.isOnGround() && !player.isGliding() && !player.isTouchingWater() &&
            velocity.y > -0.1 && velocity.y < 0.1 && !player.isClimbing()) {

            // Check if player is near climbable blocks
            boolean nearClimbable = false;
            BlockPos pos = player.getBlockPos();
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y <= 2; y++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockState state = player.getEntityWorld().getBlockState(pos.add(x, y, z));
                        if (state.isOf(Blocks.LADDER) || state.isOf(Blocks.VINE) || state.isOf(Blocks.SCAFFOLDING)) {
                            nearClimbable = true;
                            break;
                        }
                    }
                }
            }

            if (!nearClimbable) {
                data.addViolation(CheckType.MOVEMENT_FLY, 2.0, "Flying detected");
            }
        }

        // Elytra boost check
        if (player.isGliding()) {
            double speed = velocity.horizontalLength();
            if (speed > 2.5) { // Max elytra speed
                data.addViolation(CheckType.MOVEMENT_ELYTRA, 1.5,
                    "Elytra speed: " + String.format("%.2f", speed));
            }
        }

        // No slow check
        if (player.isUsingItem() && data.lastVelocity != null) {
            double currentSpeed = velocity.horizontalLength();
            double lastSpeed = data.lastVelocity.horizontalLength();

            if (currentSpeed > lastSpeed * 1.1 && currentSpeed > 0.15) {
                data.addViolation(CheckType.MOVEMENT_NO_SLOW, 1.0,
                    "No slow detected while using item");
            }
        }
    }

    // Combat Checks
    private static void runCombatChecks(ServerPlayerEntity player, PlayerData data) {
        if (!ConfigManager.getConfig().antiCheat.enableCombatChecks) return;

        // Event-driven checks are handled in onAttack(). Keep this as a future extension point.
    }

    // World Interaction Checks
    private static void runWorldChecks(ServerPlayerEntity player, PlayerData data) {
        if (!ConfigManager.getConfig().antiCheat.enableWorldChecks) return;

        // Event-driven checks are handled in onBlockBreak(). Keep this as a future extension point.
    }

    // Public API
    public static void reportViolation(UUID playerId, CheckType type, double severity, String details) {
        PlayerData data = playerData.get(playerId);
        if (data != null) {
            data.addViolation(type, severity, details);
        }
    }

    public static PlayerData getPlayerData(UUID playerId) {
        return playerData.get(playerId);
    }

    public static void freezePlayer(UUID playerId) {
        frozenPlayers.put(playerId, System.currentTimeMillis());
        if (ConfigManager.getConfig().antiCheat.enableDiscordAlerts) {
            DiscordManager.sendAnticheatAlert("Player <@" + DiscordManager.getDiscordId(playerId) + "> frozen for suspicious activity!", false);
        }
    }

    public static void unfreezePlayer(UUID playerId) {
        frozenPlayers.remove(playerId);
        if (ConfigManager.getConfig().antiCheat.enableDiscordAlerts) {
            DiscordManager.sendAnticheatAlert("Player <@" + DiscordManager.getDiscordId(playerId) + "> unfrozen", false);
        }
    }

    public static boolean isFrozen(UUID playerId) {
        return frozenPlayers.containsKey(playerId);
    }

    public static void resetViolations(UUID playerId) {
        PlayerData data = playerData.get(playerId);
        if (data != null) {
            data.violationLevel = 0;
            data.checkViolations.clear();
            data.recentViolations.clear();
        }
    }

    public static Map<UUID, PlayerData> getAllPlayerData() {
        return new HashMap<>(playerData);
    }

    // Rate limiting for API calls
    public static boolean checkRateLimit(String identifier, int maxCalls, long timeWindowMs) {
        long currentTime = System.currentTimeMillis();
        String key = identifier + ":" + (currentTime / timeWindowMs);

        // This is a simple implementation - in production you'd want a more sophisticated rate limiter
        return ipRateLimits.compute(key, (k, v) -> {
            if (v == null) return 1L;
            if (v >= maxCalls) return v; // Still over limit
            return v + 1;
        }) <= maxCalls;
    }

    private static void kickPlayer(UUID playerId, String reason) {
        MinecraftServer server = serverRef;
        if (server == null) return;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            player.networkHandler.disconnect(Text.literal(reason));
        }
    }

    private static void banPlayer(UUID playerId, String reason) {
        MinecraftServer server = serverRef;
        if (server == null) return;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) return;

        String playerName = player.getName().getString();
        server.getCommandManager().parseAndExecute(server.getCommandSource(), "ban " + playerName + " " + reason);
        player.networkHandler.disconnect(Text.literal(reason));
    }
}
