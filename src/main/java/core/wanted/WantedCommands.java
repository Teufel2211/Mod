package core.wanted;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import core.menu.MenuCommands;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

public class WantedCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wanted")
            .executes(context -> openWanted(context.getSource()))
            .then(CommandManager.literal("check")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(WantedCommands::check)))
            // Convenience: /wanted <player>
            .then(CommandManager.argument("player", EntityArgumentType.player()).executes(WantedCommands::check))
            .then(CommandManager.literal("list").executes(ctx -> list(ctx, 1))
                .then(CommandManager.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
            .then(CommandManager.literal("add")
                .requires(WantedCommands::isAdmin)
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(ctx -> add(ctx, 1, WantedManager.WantedReason.ADMIN.name()))
                    .then(CommandManager.argument("delta", IntegerArgumentType.integer(1, 5))
                        .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "delta"), WantedManager.WantedReason.ADMIN.name()))
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "delta"), StringArgumentType.getString(ctx, "reason")))))))
            .then(CommandManager.literal("set")
                .requires(WantedCommands::isAdmin)
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 5))
                        .executes(ctx -> set(ctx, IntegerArgumentType.getInteger(ctx, "level"), WantedManager.WantedReason.ADMIN.name()))
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> set(ctx, IntegerArgumentType.getInteger(ctx, "level"), StringArgumentType.getString(ctx, "reason")))))))
            .then(CommandManager.literal("remove")
                .requires(WantedCommands::isAdmin)
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(WantedCommands::remove))));
    }

    private static int openWanted(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return MenuCommands.openWantedMenu(source);
        }
        WantedGui.open(player, 0);
        return 1;
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    private static int check(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        WantedManager.WantedEntry entry = WantedManager.getEntry(player.getUuid());
        if (entry == null) {
            context.getSource().sendMessage(Text.literal("§a" + player.getName().getString() + " is not wanted."));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§c" + player.getName().getString() + " is WANTED §7(level " + entry.level + ")"));
        if (entry.reasons != null && !entry.reasons.isEmpty()) {
            context.getSource().sendMessage(Text.literal("§7Reasons: §f" + String.join(", ", entry.reasons)));
        }
        return 1;
    }

    private static int add(CommandContext<ServerCommandSource> context, int delta, String reason) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        WantedManager.WantedReason parsed = WantedManager.WantedReason.ADMIN;
        if (reason != null && !reason.isBlank()) {
            try {
                parsed = WantedManager.WantedReason.valueOf(reason.trim().toUpperCase());
            } catch (Exception ignored) {
                // keep ADMIN
            }
        }
        WantedManager.addWanted(player.getUuid(), parsed, delta);
        if (reason != null && !reason.isBlank()) {
            WantedManager.WantedEntry entry = WantedManager.getEntry(player.getUuid());
            if (entry != null) {
                if (entry.reasons == null) entry.reasons = new java.util.ArrayList<>();
                if (!entry.reasons.contains(reason)) entry.reasons.add(reason);
            }
        }
        context.getSource().sendMessage(Text.literal("§eAdded wanted level to " + player.getName().getString() + "."));
        return 1;
    }

    private static int set(CommandContext<ServerCommandSource> context, int level, String reason) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        long durationMs = core.config.ConfigManager.getConfig() != null ? core.config.ConfigManager.getConfig().wanted.wantedDuration : 3600000L;
        WantedManager.setWanted(player.getUuid(), level, durationMs, reason);
        context.getSource().sendMessage(Text.literal("§eSet wanted level of " + player.getName().getString() + " to " + level + "."));
        return 1;
    }

    private static int remove(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        WantedManager.removeWanted(player.getUuid());
        context.getSource().sendMessage(Text.literal("§eRemoved wanted status from " + player.getName().getString() + "."));
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> context, int page) {
        Map<UUID, WantedManager.WantedEntry> all = WantedManager.snapshot();
        if (all.isEmpty()) {
            context.getSource().sendMessage(Text.literal("§eNo wanted players."));
            return 1;
        }
        int pageSize = 10;
        int pageIndex = Math.max(1, page);
        var sorted = all.entrySet().stream()
            .sorted(Comparator.<Map.Entry<UUID, WantedManager.WantedEntry>>comparingInt(e -> e.getValue().level).reversed())
            .toList();
        int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) pageSize));
        if (pageIndex > totalPages) pageIndex = totalPages;
        context.getSource().sendMessage(Text.literal("§6Wanted Players §7Page " + pageIndex + "/" + totalPages));
        int start = (pageIndex - 1) * pageSize;
        int end = Math.min(sorted.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            UUID id = sorted.get(i).getKey();
            WantedManager.WantedEntry entry = sorted.get(i).getValue();
            String name = playerName(context.getSource(), id);
            context.getSource().sendMessage(Text.literal("§e" + (i + 1) + ". §f" + name + " §7- §cLevel " + entry.level));
        }
        return 1;
    }

    private static String playerName(ServerCommandSource source, UUID uuid) {
        var online = source.getServer().getPlayerManager().getPlayer(uuid);
        return online != null ? online.getName().getString() : uuid.toString().substring(0, 8);
    }
}
