package core.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class EconomyCommands {
    private EconomyCommands() {}

    private static final SuggestionProvider<ServerCommandSource> CURRENCY_SUGGESTIONS =
        (context, builder) -> CommandSource.suggestMatching(
            Arrays.stream(EconomyManager.Currency.values())
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .toList(),
            builder
        );

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(CommandManager.literal("balance").executes(EconomyCommands::balance)
            .then(CommandManager.argument("currency", StringArgumentType.word())
                .suggests(CURRENCY_SUGGESTIONS)
                .executes(EconomyCommands::balance)));
        dispatcher.register(CommandManager.literal("bal").executes(EconomyCommands::balance)
            .then(CommandManager.argument("currency", StringArgumentType.word())
                .suggests(CURRENCY_SUGGESTIONS)
                .executes(EconomyCommands::balance)));
        dispatcher.register(CommandManager.literal("money").executes(EconomyCommands::balance)
            .then(CommandManager.argument("currency", StringArgumentType.word())
                .suggests(CURRENCY_SUGGESTIONS)
                .executes(EconomyCommands::balance)));

        dispatcher.register(CommandManager.literal("pay")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.01))
                    .executes(EconomyCommands::pay)
                    .then(CommandManager.argument("currency", StringArgumentType.word())
                        .suggests(CURRENCY_SUGGESTIONS)
                        .executes(EconomyCommands::pay)))));

        dispatcher.register(CommandManager.literal("eco")
            .requires(source -> hasPermission(source, 2))
            .then(CommandManager.literal("set")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(EconomyCommands::setBalance)
                        .then(CommandManager.argument("currency", StringArgumentType.word()).suggests(CURRENCY_SUGGESTIONS).executes(EconomyCommands::setBalance)))))
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(EconomyCommands::addBalance)
                        .then(CommandManager.argument("currency", StringArgumentType.word()).suggests(CURRENCY_SUGGESTIONS).executes(EconomyCommands::addBalance)))))
            .then(CommandManager.literal("take")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(EconomyCommands::takeBalance)
                        .then(CommandManager.argument("currency", StringArgumentType.word()).suggests(CURRENCY_SUGGESTIONS).executes(EconomyCommands::takeBalance)))))
            .then(CommandManager.literal("remove")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(EconomyCommands::takeBalance)
                        .then(CommandManager.argument("currency", StringArgumentType.word()).suggests(CURRENCY_SUGGESTIONS).executes(EconomyCommands::takeBalance))))));

        dispatcher.register(CommandManager.literal("baltop")
            .executes(ctx -> baltop(ctx, EconomyManager.Currency.COINS, 1))
            .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> baltop(ctx, EconomyManager.Currency.COINS, IntegerArgumentType.getInteger(ctx, "page")))
                .then(CommandManager.argument("currency", StringArgumentType.word())
                    .suggests(CURRENCY_SUGGESTIONS)
                    .executes(ctx -> baltop(ctx, getCurrencyOrDefault(ctx, EconomyManager.Currency.COINS), IntegerArgumentType.getInteger(ctx, "page")))))
            .then(CommandManager.argument("currency", StringArgumentType.word())
                .suggests(CURRENCY_SUGGESTIONS)
                .executes(ctx -> baltop(ctx, getCurrencyOrDefault(ctx, EconomyManager.Currency.COINS), 1)))
        );

        dispatcher.register(CommandManager.literal("shop").executes(EconomyCommands::openShop));

        AuctionCommands.register(dispatcher);
        ShopAdminCommands.register(dispatcher);
    }

    private static boolean hasPermission(ServerCommandSource source, int level) {
        PermissionLevel permLevel = switch (level) {
            case 0 -> PermissionLevel.ALL;
            case 1 -> PermissionLevel.MODERATORS;
            case 2 -> PermissionLevel.ADMINS;
            default -> PermissionLevel.OWNERS;
        };
        return source.getPermissions().hasPermission(new Permission.Level(permLevel));
    }

    private static EconomyManager.Currency getCurrencyOrDefault(CommandContext<ServerCommandSource> context, EconomyManager.Currency fallback) {
        try {
            String raw = StringArgumentType.getString(context, "currency");
            EconomyManager.Currency parsed = EconomyManager.Currency.parseOrNull(raw);
            return parsed != null ? parsed : fallback;
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int balance(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) return 0;

        EconomyManager.Currency currency = getCurrencyOrDefault(context, EconomyManager.Currency.COINS);
        BigDecimal bal = EconomyManager.getBalance(player.getUuid(), currency);
        context.getSource().sendMessage(Text.literal(currency.displayName + ": " + EconomyManager.formatCurrencyCompact(bal, currency)));
        return 1;
    }

    private static int pay(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity sender = context.getSource().getPlayer();
        if (sender == null) return 0;

        try {
            ServerPlayerEntity receiver = EntityArgumentType.getPlayer(context, "player");
            double amountRaw = DoubleArgumentType.getDouble(context, "amount");
            EconomyManager.Currency currency = getCurrencyOrDefault(context, EconomyManager.Currency.COINS);

            if (sender.getUuid().equals(receiver.getUuid())) {
                context.getSource().sendMessage(Text.literal("§cYou cannot pay yourself!"));
                return 0;
            }
            if (amountRaw <= 0) {
                context.getSource().sendMessage(Text.literal("§cAmount must be positive!"));
                return 0;
            }

            BigDecimal amount = BigDecimal.valueOf(amountRaw).setScale(2, RoundingMode.HALF_UP);
            boolean ok = EconomyManager.transfer(sender.getUuid(), receiver.getUuid(), currency, amount, EconomyManager.TransactionType.PAY, "Player payment");
            if (ok) {
                String formatted = EconomyManager.formatCurrency(amount, currency);
                context.getSource().sendMessage(Text.literal("Paid " + formatted + " to " + receiver.getName().getString()));
                receiver.sendMessage(Text.literal("Received " + formatted + " from " + sender.getName().getString()));
            } else {
                context.getSource().sendMessage(Text.literal("§cInsufficient funds!"));
            }
        } catch (Exception e) {
            context.getSource().sendMessage(Text.literal("§cError processing payment!"));
        }

        return 1;
    }

    private static int setBalance(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
            double amountRaw = DoubleArgumentType.getDouble(context, "amount");
            EconomyManager.Currency currency = getCurrencyOrDefault(context, EconomyManager.Currency.COINS);

            BigDecimal amount = BigDecimal.valueOf(amountRaw).setScale(2, RoundingMode.HALF_UP);
            EconomyManager.setBalance(player.getUuid(), currency, amount);
            context.getSource().sendMessage(Text.literal("Set " + player.getName().getString() + "'s " + currency.displayName + " to " + EconomyManager.formatCurrency(amount, currency)));
        } catch (Exception e) {
            context.getSource().sendMessage(Text.literal("§cError setting balance!"));
        }
        return 1;
    }

    private static int addBalance(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
            double amountRaw = DoubleArgumentType.getDouble(context, "amount");
            EconomyManager.Currency currency = getCurrencyOrDefault(context, EconomyManager.Currency.COINS);

            BigDecimal amount = BigDecimal.valueOf(amountRaw).setScale(2, RoundingMode.HALF_UP);
            EconomyManager.addBalance(player.getUuid(), currency, amount);
            context.getSource().sendMessage(Text.literal("Added " + EconomyManager.formatCurrency(amount, currency) + " to " + player.getName().getString()));
        } catch (Exception e) {
            context.getSource().sendMessage(Text.literal("§cError adding balance!"));
        }
        return 1;
    }

    private static int takeBalance(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
            double amountRaw = DoubleArgumentType.getDouble(context, "amount");
            EconomyManager.Currency currency = getCurrencyOrDefault(context, EconomyManager.Currency.COINS);

            BigDecimal amount = BigDecimal.valueOf(amountRaw).setScale(2, RoundingMode.HALF_UP);
            boolean ok = EconomyManager.chargePlayer(player.getUuid(), currency, amount, EconomyManager.TransactionType.ADMIN_TAKE, "Admin charge");
            if (ok) {
                context.getSource().sendMessage(Text.literal("Removed " + EconomyManager.formatCurrency(amount, currency) + " from " + player.getName().getString()));
            } else {
                context.getSource().sendMessage(Text.literal("§cPlayer doesn't have enough money!"));
            }
        } catch (Exception e) {
            context.getSource().sendMessage(Text.literal("§cError taking balance!"));
        }
        return 1;
    }

    private static int openShop(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) return 0;

        ShopGui.open(player);
        return 1;
    }

    private static int baltop(CommandContext<ServerCommandSource> context, EconomyManager.Currency currency, int page) {
        int pageSize = 10;
        int pageIndex = Math.max(1, page);

        Map<UUID, BigDecimal> snapshot = EconomyManager.snapshotBalances(currency);
        var sorted = snapshot.entrySet().stream()
            .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) pageSize));
        if (pageIndex > totalPages) pageIndex = totalPages;

        int start = (pageIndex - 1) * pageSize;
        int end = Math.min(sorted.size(), start + pageSize);

        context.getSource().sendMessage(Text.literal("§6Top Balances (" + currency.displayName + ") §7Page " + pageIndex + "/" + totalPages));
        for (int i = start; i < end; i++) {
            var entry = sorted.get(i);
            var server = context.getSource().getServer();
            var online = server.getPlayerManager().getPlayer(entry.getKey());
            String name = online != null ? online.getName().getString() : entry.getKey().toString().substring(0, 8);
            context.getSource().sendMessage(Text.literal("§e" + (i + 1) + ". §f" + name + " §7- §a" + EconomyManager.formatCurrencyCompact(entry.getValue(), currency)));
        }
        return 1;
    }
}
