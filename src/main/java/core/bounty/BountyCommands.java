package core.bounty;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import core.menu.MenuCommands;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import core.economy.EconomyManager;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

public class BountyCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("bounty")
            .executes(context -> openBounty(context.getSource()))
            .then(CommandManager.literal("set")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(ctx -> set(ctx, "Manual bounty", false))
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "reason"), false))
                            .then(CommandManager.argument("anonymous", IntegerArgumentType.integer(0, 1))
                                .executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "reason"), IntegerArgumentType.getInteger(ctx, "anonymous") == 1)))))))
            // Convenience: /bounty <player> <amount>
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.01))
                    .executes(ctx -> set(ctx, "Manual bounty", false))))
            .then(CommandManager.literal("list").executes(ctx -> list(ctx, 1))
                .then(CommandManager.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
            .then(CommandManager.literal("top").executes(ctx -> top(ctx, 1))
                .then(CommandManager.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> top(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
            .then(CommandManager.literal("clear")
                .requires(BountyCommands::isAdmin)
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(BountyCommands::clear))));
    }

    private static int openBounty(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return MenuCommands.openBountyMenu(source);
        }
        BountyGui.open(player, 0);
        return 1;
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    private static int set(CommandContext<ServerCommandSource> context, String reason, boolean anonymous) throws CommandSyntaxException {
        ServerPlayerEntity setter = context.getSource().getPlayer();
        if (setter == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        double amount = DoubleArgumentType.getDouble(context, "amount");
        boolean ok = BountyManager.placeBounty(target.getUuid(), setter.getUuid(), amount, reason, anonymous);
        if (ok) {
            context.getSource().sendMessage(Text.literal("§aBounty placed on " + target.getName().getString() + " for " + EconomyManager.formatCurrency(amount) + "."));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§cBounty failed (check amount/limits/discord-link/funds)."));
        return 0;
    }

    private static int list(CommandContext<ServerCommandSource> context, int page) {
        Map<UUID, Double> all = BountyManager.getAllBounties();
        if (all.isEmpty()) {
            context.getSource().sendMessage(Text.literal("§eNo active bounties."));
            return 1;
        }
        int pageSize = 10;
        int pageIndex = Math.max(1, page);
        var sorted = all.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
            .toList();
        int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) pageSize));
        if (pageIndex > totalPages) pageIndex = totalPages;

        context.getSource().sendMessage(Text.literal("§6Bounties §7Page " + pageIndex + "/" + totalPages));
        int start = (pageIndex - 1) * pageSize;
        int end = Math.min(sorted.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            UUID id = sorted.get(i).getKey();
            double amount = sorted.get(i).getValue();
            String name = playerName(context.getSource(), id);
            context.getSource().sendMessage(Text.literal("§e" + (i + 1) + ". §f" + name + " §7- §a" + EconomyManager.formatCurrency(amount)));
        }
        return 1;
    }

    private static int top(CommandContext<ServerCommandSource> context, int page) {
        return list(context, page);
    }

    private static int clear(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        boolean had = BountyManager.getTotalBounty(target.getUuid()) > 0.0;
        if (had) {
            // Remove by claiming with dummy killer -> instead directly remove.
            // We keep it simple here: system bounties and player bounties are cleared.
            BountyManager.clearBounties(target.getUuid());
            context.getSource().sendMessage(Text.literal("§eCleared bounties for " + target.getName().getString() + "."));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§eNo bounties for " + target.getName().getString() + "."));
        return 1;
    }

    private static String playerName(ServerCommandSource source, UUID uuid) {
        var online = source.getServer().getPlayerManager().getPlayer(uuid);
        return online != null ? online.getName().getString() : uuid.toString().substring(0, 8);
    }
}
