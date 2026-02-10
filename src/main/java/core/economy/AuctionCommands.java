package core.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import core.menu.MenuCommands;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.util.Map;

public final class AuctionCommands {
    private AuctionCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("auction")
            .executes(context -> MenuCommands.openAuctionMenu(context.getSource()))
            .then(CommandManager.literal("list").executes(AuctionCommands::list))
            .then(CommandManager.literal("sell")
                .then(CommandManager.argument("startingBid", DoubleArgumentType.doubleArg(0.01))
                    .executes(AuctionCommands::sell)
                    .then(CommandManager.argument("buyoutPrice", DoubleArgumentType.doubleArg(0.01))
                        .executes(AuctionCommands::sellWithBuyout))))
            .then(CommandManager.literal("bid")
                .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                    .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(AuctionCommands::bid))))
            .then(CommandManager.literal("buy")
                .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                    .executes(AuctionCommands::buyNow)))
            .then(CommandManager.literal("cancel")
                .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                    .executes(AuctionCommands::cancel)))
            .then(CommandManager.literal("claim").executes(AuctionCommands::claim)));
    }

    private static int list(CommandContext<ServerCommandSource> context) {
        Map<Integer, AuctionManager.Auction> active = AuctionManager.getActiveAuctions();
        if (active.isEmpty()) {
            context.getSource().sendMessage(Text.literal("§eNo active auctions."));
            return 1;
        }

        context.getSource().sendMessage(Text.literal("§6Active Auctions (" + active.size() + "):"));
        long now = System.currentTimeMillis();
        for (AuctionManager.Auction a : active.values()) {
            long minutesLeft = Math.max(0, (a.endTime - now) / 60000L);
            String itemName = a.item.getName().getString();
            String buyout = a.buyoutPrice == null ? "" : (" §bBuyout: §f" + EconomyManager.formatCurrency(a.buyoutPrice.doubleValue()));
            context.getSource().sendMessage(Text.literal("§7#" + a.id + " §f" + itemName +
                " §7x" + a.item.getCount() +
                " §eCurrent: §f" + EconomyManager.formatCurrency(a.currentBid.doubleValue()) +
                buyout +
                " §7Ends in: §f" + minutesLeft + "m"));
        }
        return 1;
    }

    private static int sell(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }

        double startingBid = DoubleArgumentType.getDouble(context, "startingBid");
        int id = AuctionManager.createAuctionFromPlayer(player, BigDecimal.valueOf(startingBid));
        if (id < 0) {
            context.getSource().sendMessage(Text.literal("§cCould not create auction (missing item or insufficient funds for fee)."));
            return 0;
        }

        context.getSource().sendMessage(Text.literal("§aCreated auction #" + id + " with starting bid " +
            EconomyManager.formatCurrency(startingBid) + "."));
        return 1;
    }

    private static int sellWithBuyout(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }

        double startingBid = DoubleArgumentType.getDouble(context, "startingBid");
        double buyout = DoubleArgumentType.getDouble(context, "buyoutPrice");
        if (buyout < startingBid) {
            context.getSource().sendMessage(Text.literal("§cBuyout price must be >= starting bid."));
            return 0;
        }

        int id = AuctionManager.createAuctionFromPlayer(player, BigDecimal.valueOf(startingBid), BigDecimal.valueOf(buyout));
        if (id < 0) {
            context.getSource().sendMessage(Text.literal("§cCould not create auction (missing item or insufficient funds for fee)."));
            return 0;
        }

        context.getSource().sendMessage(Text.literal("§aCreated auction #" + id + " with starting bid " +
            EconomyManager.formatCurrency(startingBid) + " and buyout " + EconomyManager.formatCurrency(buyout) + "."));
        return 1;
    }

    private static int bid(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }

        int id = IntegerArgumentType.getInteger(context, "id");
        double amount = DoubleArgumentType.getDouble(context, "amount");

        boolean ok = AuctionManager.bidOnAuction(player.getUuid(), id, BigDecimal.valueOf(amount));
        if (!ok) {
            context.getSource().sendMessage(Text.literal("§cBid failed (auction missing/ended, bid too low, or insufficient funds)."));
            return 0;
        }

        context.getSource().sendMessage(Text.literal("§aBid placed: " + EconomyManager.formatCurrency(amount) + " on auction #" + id + "."));
        return 1;
    }

    private static int buyNow(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }

        int id = IntegerArgumentType.getInteger(context, "id");
        AuctionManager.BuyNowResult result = AuctionManager.buyNow(player, id);
        return switch (result) {
            case SUCCESS -> {
                context.getSource().sendMessage(Text.literal("§aBought auction #" + id + " instantly."));
                yield 1;
            }
            case NOT_FOUND -> {
                context.getSource().sendMessage(Text.literal("§cAuction not found."));
                yield 0;
            }
            case NOT_ALLOWED -> {
                context.getSource().sendMessage(Text.literal("§cYou can't buy your own auction."));
                yield 0;
            }
            case NO_BUYOUT -> {
                context.getSource().sendMessage(Text.literal("§cThis auction has no buyout price."));
                yield 0;
            }
            case INSUFFICIENT_FUNDS -> {
                context.getSource().sendMessage(Text.literal("§cInsufficient funds."));
                yield 0;
            }
            case ERROR -> {
                context.getSource().sendMessage(Text.literal("§cBuyout failed."));
                yield 0;
            }
        };
    }

    private static int cancel(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }

        int id = IntegerArgumentType.getInteger(context, "id");
        boolean isAdmin = hasPermission(context.getSource(), 2);

        AuctionManager.CancelResult result = AuctionManager.cancelAuction(player, id, isAdmin);
        return switch (result) {
            case NOT_FOUND -> {
                context.getSource().sendMessage(Text.literal("§cAuction not found."));
                yield 0;
            }
            case NOT_ALLOWED -> {
                context.getSource().sendMessage(Text.literal("§cYou can't cancel this auction."));
                yield 0;
            }
            case HAS_BIDS -> {
                context.getSource().sendMessage(Text.literal("§cCan't cancel: auction already has bids."));
                yield 0;
            }
            case CANCELED -> {
                context.getSource().sendMessage(Text.literal("§aAuction canceled."));
                yield 1;
            }
        };
    }

    private static int claim(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§cThis command can only be used by players."));
            return 0;
        }

        int claimed = AuctionManager.claimDeliveries(player);
        if (claimed <= 0) {
            context.getSource().sendMessage(Text.literal("§eNo auction items to claim."));
            return 1;
        }

        context.getSource().sendMessage(Text.literal("§aClaimed " + claimed + " auction item(s)."));
        return 1;
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
}
