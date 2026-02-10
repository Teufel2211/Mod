package core.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ShopAdminCommands {
    private ShopAdminCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = CommandManager.literal("shopadmin").requires(ShopAdminCommands::isAdmin);

        var add = CommandManager.literal("add");
        var addItem = CommandManager.argument("item", IdentifierArgumentType.identifier());
        var addBuy = CommandManager.argument("buyPrice", DoubleArgumentType.doubleArg(0.0));
        var addSell = CommandManager.argument("sellPrice", DoubleArgumentType.doubleArg(0.0));

        // /shopadmin add <item> <buy> <sell> [stock] [category] [minPermLevel]
        var addBase = addSell.executes(ctx -> add(ctx, -1, "General", 0));

        var addWithCategory = CommandManager.argument("category", StringArgumentType.word())
            .executes(ctx -> add(ctx, -1, StringArgumentType.getString(ctx, "category"), 0))
            .then(CommandManager.argument("minPermLevel", IntegerArgumentType.integer(0, 4))
                .executes(ctx -> add(ctx, -1, StringArgumentType.getString(ctx, "category"), IntegerArgumentType.getInteger(ctx, "minPermLevel"))));

        var addWithStock = CommandManager.argument("stock", IntegerArgumentType.integer(-1))
            .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "stock"), "General", 0))
            .then(CommandManager.argument("category", StringArgumentType.word())
                .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "stock"), StringArgumentType.getString(ctx, "category"), 0))
                .then(CommandManager.argument("minPermLevel", IntegerArgumentType.integer(0, 4))
                    .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "stock"), StringArgumentType.getString(ctx, "category"), IntegerArgumentType.getInteger(ctx, "minPermLevel")))));

        addBase.then(addWithStock);
        addBase.then(addWithCategory);
        addBuy.then(addBase);
        addItem.then(addBuy);
        add.then(addItem);
        root.then(add);

        root.then(CommandManager.literal("remove")
            .then(CommandManager.argument("item", IdentifierArgumentType.identifier()).executes(ShopAdminCommands::remove)));

        root.then(CommandManager.literal("setprice")
            .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("buyPrice", DoubleArgumentType.doubleArg(0.0))
                    .then(CommandManager.argument("sellPrice", DoubleArgumentType.doubleArg(0.0))
                        .executes(ShopAdminCommands::setPrice)))));

        root.then(CommandManager.literal("setstock")
            .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("stock", IntegerArgumentType.integer(-1))
                    .executes(ShopAdminCommands::setStock))));

        root.then(CommandManager.literal("setcategory")
            .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("category", StringArgumentType.word())
                    .executes(ShopAdminCommands::setCategory))));

        root.then(CommandManager.literal("setpermlevel")
            .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("minPermLevel", IntegerArgumentType.integer(0, 4))
                    .executes(ShopAdminCommands::setPermLevel))));

        root.then(CommandManager.literal("info")
            .then(CommandManager.argument("item", IdentifierArgumentType.identifier()).executes(ShopAdminCommands::info)));

        root.then(CommandManager.literal("list").executes(ShopAdminCommands::list));

        dispatcher.register(root);
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    private static int add(CommandContext<ServerCommandSource> context, int stock, String category, int minPermLevel) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        double buy = DoubleArgumentType.getDouble(context, "buyPrice");
        double sell = DoubleArgumentType.getDouble(context, "sellPrice");
        String itemId = id.toString();

        ShopManager.UpsertResult result = ShopManager.upsertItem(itemId, buy, sell, stock, category, minPermLevel);
        return switch (result) {
            case SUCCESS -> {
                context.getSource().sendMessage(Text.literal("§aAdded/updated shop item: " + itemId));
                yield 1;
            }
            case INVALID_ITEM -> {
                context.getSource().sendMessage(Text.literal("§cUnknown item id: " + itemId));
                yield 0;
            }
            case INVALID_PRICE -> {
                context.getSource().sendMessage(Text.literal("§cInvalid price values."));
                yield 0;
            }
            case ERROR -> {
                context.getSource().sendMessage(Text.literal("§cFailed to update shop."));
                yield 0;
            }
        };
    }

    private static int remove(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        boolean removed = ShopManager.removeItem(itemId);
        if (removed) {
            context.getSource().sendMessage(Text.literal("§eRemoved shop item: " + itemId));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§cShop item not found: " + itemId));
        return 0;
    }

    private static int setPrice(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        double buy = DoubleArgumentType.getDouble(context, "buyPrice");
        double sell = DoubleArgumentType.getDouble(context, "sellPrice");

        ShopManager.UpsertResult result = ShopManager.updatePrices(itemId, buy, sell);
        if (result == ShopManager.UpsertResult.SUCCESS) {
            context.getSource().sendMessage(Text.literal("§aUpdated prices for: " + itemId));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§cFailed to update prices (missing item or invalid values)."));
        return 0;
    }

    private static int setStock(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        int stock = IntegerArgumentType.getInteger(context, "stock");

        boolean ok = ShopManager.updateStock(itemId, stock);
        if (ok) {
            context.getSource().sendMessage(Text.literal("§aUpdated stock for: " + itemId + " to " + stock));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§cShop item not found: " + itemId));
        return 0;
    }

    private static int setCategory(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        String category = StringArgumentType.getString(context, "category");

        boolean ok = ShopManager.updateCategory(itemId, category);
        if (ok) {
            context.getSource().sendMessage(Text.literal("§aUpdated category for: " + itemId + " to " + category));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§cShop item not found: " + itemId));
        return 0;
    }

    private static int setPermLevel(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        int level = IntegerArgumentType.getInteger(context, "minPermLevel");

        boolean ok = ShopManager.updateMinPermissionLevel(itemId, level);
        if (ok) {
            context.getSource().sendMessage(Text.literal("§aUpdated min permission level for: " + itemId + " to " + level));
            return 1;
        }
        context.getSource().sendMessage(Text.literal("§cShop item not found: " + itemId));
        return 0;
    }

    private static int info(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        ShopManager.ShopItem item = ShopManager.getShopItem(itemId);
        if (item == null) {
            context.getSource().sendMessage(Text.literal("§cShop item not found: " + itemId));
            return 0;
        }

        String stock = item.stock < 0 ? "infinite" : Integer.toString(item.stock);
        context.getSource().sendMessage(Text.literal("§6" + itemId + " §7buy=" + item.buyPrice + " sell=" + item.sellPrice + " stock=" + stock + " category=" + item.category + " permLevel=" + item.minPermissionLevel));
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> context) {
        var items = ShopManager.getShopItems();
        context.getSource().sendMessage(Text.literal("§6Shop items: §f" + items.size()));
        return 1;
    }
}
