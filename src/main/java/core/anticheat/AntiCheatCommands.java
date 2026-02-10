package core.anticheat;

import com.mojang.brigadier.CommandDispatcher;
import core.config.ConfigManager;
import core.menu.MenuCommands;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

public class AntiCheatCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("ac")
            .executes(context -> openAntiCheat(context.getSource()))
            .then(CommandManager.literal("help")
                .executes(context -> {
                    context.getSource().sendMessage(Text.literal("Anti-Cheat Commands: /ac help, /ac check <player>, /ac reset <player>, /ac alerts, /ac reload"));
                    return 1;
                }))
            .then(CommandManager.literal("check")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(context -> {
                        var player = EntityArgumentType.getPlayer(context, "player");
                        var data = AntiCheatManager.getViolations(player.getUuid());
                        if (data != null) {
                            context.getSource().sendMessage(Text.literal("Violations: " + data.getViolationLevels()));
                        } else {
                            context.getSource().sendMessage(Text.literal("No violations"));
                        }
                        return 1;
                    })))
            .then(CommandManager.literal("reset")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(context -> {
                        var player = EntityArgumentType.getPlayer(context, "player");
                        AntiCheatManager.resetViolations(player.getUuid());
                        context.getSource().sendMessage(Text.literal("Violations reset"));
                        return 1;
                    })))
            .then(CommandManager.literal("ignore")
                .requires(AntiCheatCommands::isAdmin)
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            String uuid = target.getUuid().toString();
                            List<String> ignored = ConfigManager.getConfig().antiCheat.ignoredPlayers;
                            if (!ignored.contains(uuid)) {
                                ignored.add(uuid);
                                ConfigManager.saveConfig();
                            }
                            context.getSource().sendMessage(Text.literal("§aAnti-cheat now ignores " + target.getName().getString() + "."));
                            return 1;
                        })))
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            String uuid = target.getUuid().toString();
                            List<String> ignored = ConfigManager.getConfig().antiCheat.ignoredPlayers;
                            ignored.remove(uuid);
                            ConfigManager.saveConfig();
                            context.getSource().sendMessage(Text.literal("§eAnti-cheat no longer ignores " + target.getName().getString() + "."));
                            return 1;
                        })))
                .then(CommandManager.literal("list")
                    .executes(context -> {
                        List<String> ignored = ConfigManager.getConfig().antiCheat.ignoredPlayers;
                        if (ignored == null || ignored.isEmpty()) {
                            context.getSource().sendMessage(Text.literal("§eNo ignored players."));
                            return 1;
                        }
                        context.getSource().sendMessage(Text.literal("§6Ignored players (" + ignored.size() + "):"));
                        for (String uuid : ignored) {
                            context.getSource().sendMessage(Text.literal("§7- " + uuid));
                        }
                        return 1;
                    })))
            .then(CommandManager.literal("alerts")
                .executes(context -> {
                    // List alerts
                    context.getSource().sendMessage(Text.literal("Alerts: (placeholder)"));
                    return 1;
                }))
            .then(CommandManager.literal("reload")
                .executes(context -> {
                    core.config.ConfigManager.reloadConfig();
                    context.getSource().sendMessage(Text.literal("Config reloaded"));
                    return 1;
                })));
    }

    private static int openAntiCheat(ServerCommandSource source) {
        try {
            ServerPlayerEntity player = source.getPlayer();
            AntiCheatGui.open(player, 0);
            return 1;
        } catch (Exception e) {
            return MenuCommands.openAntiCheatMenu(source);
        }
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }
}
