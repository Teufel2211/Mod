package core.map;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

import net.minecraft.network.packet.s2c.play.PositionFlag;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import core.menu.MenuCommands;
import core.util.Safe;

import static core.map.MapManager.*;
import static core.map.MapPermissions.hasAdminPermission;
import static core.map.MapSecurity.*;

public class MapCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");

    private static final SuggestionProvider<ServerCommandSource> WAYPOINT_NAMES = (context, builder) -> {
        ServerPlayerEntity player = context.getSource().getPlayer();
        Map<String, MapManager.Waypoint> waypoints = (player != null) ? getVisibleWaypoints(player) : getWaypoints();
        suggestMatching(waypoints.keySet(), builder);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> WAYPOINT_TYPES = (context, builder) -> {
        suggestMatching(java.util.Arrays.asList("home", "base", "village", "mine", "farm", "shop", "portal", "temple", "fortress", "other"), builder);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> VISIBILITY_OPTIONS = (context, builder) -> {
        suggestMatching(java.util.Arrays.asList("private", "team", "global"), builder);
        return builder.buildFuture();
    };

    private static <S> void suggestMatching(Iterable<String> candidates, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(remaining)) {
                builder.suggest(candidate);
            }
        }
    }

    private static String getPlayerName(MinecraftServer server, UUID uuid) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
        if (online != null) {
            return online.getName().getString();
        }
        return uuid.toString().substring(0, 8);
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        LOGGER.info("Registering /map, /minimap, and /mapadmin commands");
        dispatcher.register(CommandManager.literal("worldmap")
            .executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player == null) {
                    context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
                    return 0;
                }
                Safe.run("WorldMapGui.open", () -> WorldMapGui.open(player, 0));
                return 1;
            }));

        dispatcher.register(CommandManager.literal("minimap")
                .then(CommandManager.literal("toggle")
                    .executes(context -> {
                        context.getSource().sendMessage(Text.literal("§eMinimap is client-side. Use keybinds (default: M to toggle, J for World Map)."));
                        return 1;
                    })));

            // /map waypoint add <name> <pos> <type> <visibility>
        dispatcher.register(CommandManager.literal("map")
                .executes(context -> MenuCommands.openMapMenu(context.getSource()))
                .then(CommandManager.literal("open")
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
                            return 0;
                        }
                        Safe.run("WorldMapGui.open", () -> WorldMapGui.open(player, 0));
                        return 1;
                    }))
                .then(CommandManager.literal("waypoint")
                    .then(CommandManager.literal("add")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .then(CommandManager.argument("type", StringArgumentType.word())
                                    .suggests(WAYPOINT_TYPES)
                                    .then(CommandManager.argument("visibility", StringArgumentType.word())
                                        .suggests(VISIBILITY_OPTIONS)
                                        .executes(context -> {
                                            ServerCommandSource source = context.getSource();
                                            String name = StringArgumentType.getString(context, "name");
                                            BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
                                            String type = StringArgumentType.getString(context, "type");
                                            String visibility = StringArgumentType.getString(context, "visibility");

                                            if (source.getPlayer() == null) {
                                                source.sendMessage(Text.literal("§cThis command can only be used by players."));
                                                return 0;
                                            }

                                            // Security checks
                                            if (!checkRateLimit(source.getPlayer())) {
                                                source.sendMessage(Text.literal("§cYou're performing actions too quickly. Please wait."));
                                                return 0;
                                            }

                                            if (!isValidWaypointName(name)) {
                                                source.sendMessage(Text.literal("§cInvalid waypoint name. Use only letters, numbers, spaces, underscores, and hyphens (max 50 chars)."));
                                                return 0;
                                            }

                                            if (!isValidWaypointType(type)) {
                                                source.sendMessage(Text.literal("§cInvalid waypoint type. Valid types: home, base, village, mine, farm, shop, portal, temple, fortress, other"));
                                                return 0;
                                            }

                                            if (!isValidVisibility(visibility)) {
                                                source.sendMessage(Text.literal("§cInvalid visibility. Valid options: private, team, global"));
                                                return 0;
                                            }

                                            if (!isValidPosition(pos)) {
                                                source.sendMessage(Text.literal("§cInvalid position."));
                                                return 0;
                                            }

                                            if (getWaypoints().containsKey(name)) {
                                                source.sendMessage(Text.literal("§cWaypoint '" + name + "' already exists."));
                                                return 0;
                                            }

                                            MapManager.addWaypoint(name, pos, source.getWorld().getRegistryKey().getValue().toString(), type, visibility, source.getPlayer().getUuid());
                                            MapNetworking.syncWaypointsToClient(source.getPlayer());
                                            source.sendMessage(Text.literal("§aWaypoint '" + name + "' added at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
                                            return 1;
                                        }))))))
                    .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .suggests(WAYPOINT_NAMES)
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                String name = StringArgumentType.getString(context, "name");

                                if (!getWaypoints().containsKey(name)) {
                                    source.sendMessage(Text.literal("§cWaypoint '" + name + "' does not exist."));
                                    return 0;
                                }

                                MapManager.removeWaypoint(name);
                                ServerPlayerEntity player = source.getPlayer();
                                if (player != null) {
                                    MapNetworking.syncWaypointsToClient(player);
                                }
                                source.sendMessage(Text.literal("§aWaypoint '" + name + "' removed."));
                                return 1;
                            })))
                    .then(CommandManager.literal("list")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            ServerPlayerEntity player = source.getPlayer();

                            Map<String, MapManager.Waypoint> wps;
                            if (player != null) {
                                wps = getVisibleWaypoints(player);
                            } else {
                                wps = getWaypoints(); // Console sees all
                            }

                            if (wps.isEmpty()) {
                                source.sendMessage(Text.literal("§eNo waypoints found."));
                            } else {
                                source.sendMessage(Text.literal("§6Waypoints:"));
                                wps.forEach((name, wp) -> {
                                    String ownerName = getPlayerName(context.getSource().getServer(), wp.owner);
                                    source.sendMessage(Text.literal("§e" + name + ": " + wp.pos.getX() + ", " + wp.pos.getY() + ", " + wp.pos.getZ() + " in " + wp.dimension + " (" + wp.type + ", " + wp.visibility + ") - Owner: " + ownerName));
                                });
                            }
                            return 1;
                        })))
                    .then(CommandManager.literal("teleport")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .suggests(WAYPOINT_NAMES)
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                ServerPlayerEntity player = source.getPlayer();
                                String name = StringArgumentType.getString(context, "name");

                                if (player == null) {
                                    source.sendMessage(Text.literal("§cThis command can only be used by players."));
                                    return 0;
                                }

                                MapManager.Waypoint wp = getWaypoint(name);
                                if (wp == null) {
                                    source.sendMessage(Text.literal("§cWaypoint '" + name + "' does not exist."));
                                    return 0;
                                }

                                if (!MapPermissions.canViewWaypoint(player, wp)) {
                                    source.sendMessage(Text.literal("§cYou don't have permission to view this waypoint."));
                                    return 0;
                                }

                                // Teleport player to waypoint
                                var worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(wp.dimension));
                                ServerWorld targetWorld = source.getServer().getWorld(worldKey);
                                if (targetWorld == null) {
                                    source.sendMessage(Text.literal("§cDimension '" + wp.dimension + "' is not loaded."));
                                    return 0;
                                }
                                player.teleport(targetWorld, wp.pos.getX() + 0.5, wp.pos.getY(), wp.pos.getZ() + 0.5, Set.<PositionFlag>of(), 0F, 0F, false);
                                source.sendMessage(Text.literal("§aTeleported to waypoint '" + name + "'."));
                                return 1;
                            })))
                    .then(CommandManager.literal("share")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .suggests(WAYPOINT_NAMES)
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(context -> {
                                    ServerCommandSource source = context.getSource();
                                    ServerPlayerEntity sender = source.getPlayer();
                                    String name = StringArgumentType.getString(context, "name");
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");

                                    if (sender == null) {
                                        source.sendMessage(Text.literal("§cThis command can only be used by players."));
                                        return 0;
                                    }

                                    MapManager.Waypoint wp = getWaypoint(name);
                                    if (wp == null) {
                                        source.sendMessage(Text.literal("§cWaypoint '" + name + "' does not exist."));
                                        return 0;
                                    }

                                    if (!wp.owner.equals(sender.getUuid())) {
                                        source.sendMessage(Text.literal("§cYou can only share your own waypoints."));
                                        return 0;
                                    }

                                    boolean shared = MapManager.shareWaypoint(name, target.getUuid());
                                    if (!shared) {
                                        source.sendMessage(Text.literal("§cWaypoint '" + name + "' was already shared with " + target.getName().getString() + "."));
                                        return 0;
                                    }
                                    source.sendMessage(Text.literal("§aWaypoint '" + name + "' shared with " + target.getName().getString() + "."));
                                    target.sendMessage(Text.literal("§a" + sender.getName().getString() + " shared waypoint '" + name + "' with you."));
                                    MapNetworking.syncWaypointsToClient(sender);
                                    MapNetworking.syncWaypointsToClient(target);
                                    return 1;
                                })))));

            // Admin commands
            dispatcher.register(CommandManager.literal("mapadmin")
                .requires(source -> (source.getPlayer() != null && hasAdminPermission(source.getPlayer())))
                .then(CommandManager.literal("listall")
                    .executes(context -> {
                        Map<String, MapManager.Waypoint> wps = getWaypoints();
                        if (wps.isEmpty()) {
                            context.getSource().sendMessage(Text.literal("§eNo waypoints found."));
                        } else {
                            context.getSource().sendMessage(Text.literal("§6All Waypoints (" + wps.size() + "):"));
                            wps.forEach((name, wp) -> {
                                String ownerName = getPlayerName(context.getSource().getServer(), wp.owner);
                                context.getSource().sendMessage(Text.literal("§c" + name + ": " + wp.pos.getX() + ", " + wp.pos.getY() + ", " + wp.pos.getZ() + " in " + wp.dimension + " (" + wp.type + ", " + wp.visibility + ") - Owner: " + ownerName));
                            });
                        }
                        return 1;
                    }))
                .then(CommandManager.literal("delete")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            if (!getWaypoints().containsKey(name)) {
                                context.getSource().sendMessage(Text.literal("§cWaypoint '" + name + "' does not exist."));
                                return 0;
                            }
                            removeWaypoint(name);
                            context.getSource().getServer().getPlayerManager().getPlayerList()
                                .forEach(MapNetworking::syncWaypointsToClient);
                            context.getSource().sendMessage(Text.literal("§aAdmin: Force deleted waypoint '" + name + "'."));
                            return 1;
                        })))
                .then(CommandManager.literal("clear")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            Map<String, MapManager.Waypoint> allWaypoints = getWaypoints();
                            int deleted = 0;
                            for (String name : allWaypoints.keySet()) {
                                if (allWaypoints.get(name).owner.equals(target.getUuid())) {
                                    removeWaypoint(name);
                                    deleted++;
                                }
                            }
                            context.getSource().sendMessage(Text.literal("§aAdmin: Deleted " + deleted + " waypoints owned by " + target.getName().getString() + "."));
                            context.getSource().getServer().getPlayerManager().getPlayerList()
                                .forEach(MapNetworking::syncWaypointsToClient);
                            return 1;
                        }))));
    }
}
