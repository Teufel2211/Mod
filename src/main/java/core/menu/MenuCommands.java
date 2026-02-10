package core.menu;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import core.config.ConfigManager;
import core.economy.EconomyMenuGui;
import core.claims.ClaimsGui;
import core.clans.ClansGui;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class MenuCommands {
    private MenuCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("menu").executes(MenuCommands::menu));
        dispatcher.register(CommandManager.literal("economy").executes(MenuCommands::economy));
        dispatcher.register(CommandManager.literal("claims").executes(context -> openClaims(context.getSource())));
        dispatcher.register(CommandManager.literal("clans").executes(context -> openClans(context.getSource())));
        dispatcher.register(CommandManager.literal("core")
            .then(CommandManager.literal("menu").executes(MenuCommands::menu)));
    }

    private static int menu(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = getPlayer(source);
        if (player != null) {
            CoreMenuGui.open(player);
            return 1;
        }

        source.sendMessage(Text.literal("====== Core Mod Menu ======").formatted(Formatting.GOLD));

        source.sendMessage(run("Economy Menu", "/economy", "Open economy menu"));
        source.sendMessage(run("Claims Menu", "/claims", "Open claims menu"));
        source.sendMessage(run("Clans Menu", "/clans", "Open clans menu"));
        source.sendMessage(run("Bounty Menu", "/bounty", "Open bounty menu"));
        source.sendMessage(run("Wanted Menu", "/wanted", "Open wanted menu"));
        source.sendMessage(run("Map Menu", "/map", "Open map menu"));
        source.sendMessage(run("Anti-Cheat Menu", "/ac", "Open anti-cheat menu"));

        source.sendMessage(Text.literal(" "));
        source.sendMessage(Text.literal("Quick actions:").formatted(Formatting.AQUA));
        source.sendMessage(run("Balance", "/balance", "Show your balance"));
        source.sendMessage(suggest("Pay", "/pay <player> <amount>", "Pay a player"));
        source.sendMessage(run("Auction List", "/auction list", "List active auctions"));

        source.sendMessage(Text.literal("Tip: click a line to run/suggest the command.").formatted(Formatting.GRAY));
        return 1;
    }

    private static int economy(CommandContext<ServerCommandSource> context) {
        return openEconomy(context.getSource());
    }

    public static int openEconomy(ServerCommandSource source) {
        ServerPlayerEntity player = getPlayer(source);
        if (player != null) {
            EconomyMenuGui.open(player);
            return 1;
        }
        return openEconomyMenu(source);
    }

    public static int openClaims(ServerCommandSource source) {
        ServerPlayerEntity player = getPlayer(source);
        if (player != null) {
            ClaimsGui.open(player);
            return 1;
        }
        return openClaimsMenu(source);
    }

    public static int openClans(ServerCommandSource source) {
        ServerPlayerEntity player = getPlayer(source);
        if (player != null) {
            ClansGui.open(player);
            return 1;
        }
        return openClansMenu(source);
    }

    public static int openEconomyMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Economy Menu ======").formatted(Formatting.GOLD));
        source.sendMessage(run("Balance", "/balance", "Show your balance"));
        source.sendMessage(suggest("Pay", "/pay <player> <amount>", "Pay a player"));
        source.sendMessage(run("Shop", "/shop", "Open the shop"));
        source.sendMessage(Text.literal(" "));
        source.sendMessage(section("Auctions"));
        source.sendMessage(run("List", "/auction list", "List active auctions"));
        source.sendMessage(suggest("Sell (in-hand)", "/auction sell <startingBid> [buyoutPrice]", "Create auction from held item (optional buyout)"));
        source.sendMessage(suggest("Bid", "/auction bid <id> <amount>", "Bid on an auction"));
        source.sendMessage(suggest("Buy Now", "/auction buy <id>", "Buy an auction instantly (if buyout is set)"));
        source.sendMessage(suggest("Cancel", "/auction cancel <id>", "Cancel your auction (no bids)"));
        source.sendMessage(run("Claim", "/auction claim", "Claim pending auction items"));
        if (hasPermission(source, PermissionLevel.ADMINS)) {
            source.sendMessage(Text.literal(" "));
            source.sendMessage(section("Admin"));
            source.sendMessage(run("Eco Admin Root", "/eco", "Economy admin commands"));
            source.sendMessage(suggest("Shop Admin Add", "/shopadmin add <item> <buyPrice> <sellPrice> [stock]", "Add any game item to the shop"));
            source.sendMessage(suggest("Shop Admin Remove", "/shopadmin remove <item>", "Remove an item from the shop"));
        }
        return 1;
    }

    public static int openAuctionMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Auction Menu ======").formatted(Formatting.GOLD));
        source.sendMessage(run("List", "/auction list", "List active auctions"));
        source.sendMessage(suggest("Sell (in-hand)", "/auction sell <startingBid> [buyoutPrice]", "Create auction from held item (optional buyout)"));
        source.sendMessage(suggest("Bid", "/auction bid <id> <amount>", "Bid on an auction"));
        source.sendMessage(suggest("Buy Now", "/auction buy <id>", "Buy an auction instantly (if buyout is set)"));
        source.sendMessage(suggest("Cancel", "/auction cancel <id>", "Cancel your auction (no bids)"));
        source.sendMessage(run("Claim", "/auction claim", "Claim pending auction items"));
        return 1;
    }

    public static int openClaimsMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Claims Menu ======").formatted(Formatting.GOLD));
        if (ConfigManager.getConfig() == null || !ConfigManager.getConfig().claim.enableClaims) {
            source.sendMessage(Text.literal("Claims are disabled in config.").formatted(Formatting.DARK_GRAY));
            return 1;
        }
        source.sendMessage(run("Claim", "/claim", "Claim the current chunk"));
        source.sendMessage(run("Unclaim", "/unclaim", "Unclaim the current chunk"));
        source.sendMessage(run("Sync", "/claim sync", "Refresh claim data (client map mods may need restart)"));
        source.sendMessage(Text.literal(" "));
        source.sendMessage(section("Trust"));
        source.sendMessage(suggest("Trust", "/trust <player>", "Trust a player in this chunk"));
        source.sendMessage(suggest("Untrust", "/untrust <player>", "Untrust a player in this chunk"));
        return 1;
    }

    public static int openClansMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Clans Menu ======").formatted(Formatting.GOLD));
        source.sendMessage(suggest("Create", "/clan create <name> <tag>", "Create a clan"));
        source.sendMessage(suggest("Join", "/clan join <name>", "Join a clan"));
        source.sendMessage(run("Leave", "/clan leave", "Leave your clan"));
        return 1;
    }

    public static int openBountyMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Bounty Menu ======").formatted(Formatting.GOLD));
        source.sendMessage(suggest("Place Bounty", "/bounty <player> <amount>", "Place a bounty on a player"));
        return 1;
    }

    public static int openWantedMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Wanted Menu ======").formatted(Formatting.GOLD));
        source.sendMessage(suggest("Check Wanted", "/wanted <player>", "Check if a player is wanted"));
        return 1;
    }

    public static int openMapMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Map Menu ======").formatted(Formatting.GOLD));
        source.sendMessage(run("Minimap Toggle", "/minimap toggle", "Toggle minimap visibility (client-side)"));
        source.sendMessage(Text.literal(" "));
        source.sendMessage(section("Waypoints"));
        source.sendMessage(run("List", "/map waypoint list", "List your waypoints"));
        source.sendMessage(suggest("Add", "/map waypoint add <name> <x> <y> <z> <type> <visibility>", "Add a waypoint"));
        source.sendMessage(suggest("Remove", "/map waypoint remove <name>", "Remove a waypoint"));
        source.sendMessage(suggest("Teleport", "/map waypoint teleport <name>", "Teleport to a waypoint"));
        source.sendMessage(suggest("Share", "/map waypoint share <name> <player>", "Share a waypoint"));
        if (hasPermission(source, PermissionLevel.ADMINS)) {
            source.sendMessage(Text.literal(" "));
            source.sendMessage(section("Admin"));
            source.sendMessage(run("Map Admin (List All)", "/mapadmin listall", "List all waypoints"));
        }
        return 1;
    }

    public static int openAntiCheatMenu(ServerCommandSource source) {
        source.sendMessage(Text.literal("====== Anti-Cheat Menu ======").formatted(Formatting.GOLD));
        if (ConfigManager.getConfig() == null || !ConfigManager.getConfig().antiCheat.enableAntiCheat) {
            source.sendMessage(Text.literal("Anti-cheat is disabled in config.").formatted(Formatting.DARK_GRAY));
            return 1;
        }
        source.sendMessage(run("Help", "/ac help", "Show anti-cheat help"));
        source.sendMessage(suggest("Check", "/ac check <player>", "Check a player's violations"));
        source.sendMessage(suggest("Reset", "/ac reset <player>", "Reset a player's violations"));
        source.sendMessage(run("Alerts", "/ac alerts", "Show alerts (placeholder)"));
        source.sendMessage(run("Reload Config", "/ac reload", "Reload config"));
        return 1;
    }

    private static Text section(String name) {
        return Text.literal("-- " + name + " --").formatted(Formatting.AQUA);
    }

    private static Text run(String label, String command, String hover) {
        return action(label, command, hover, true);
    }

    private static Text suggest(String label, String command, String hover) {
        return action(label, command, hover, false);
    }

    private static Text action(String label, String command, String hover, boolean run) {
        MutableText text = Text.literal("• " + label + ": ").formatted(Formatting.GRAY)
            .append(Text.literal(command).formatted(Formatting.YELLOW));
        return text.setStyle(text.getStyle()
            .withClickEvent(run ? new ClickEvent.RunCommand(command) : new ClickEvent.SuggestCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    private static boolean hasPermission(ServerCommandSource source, PermissionLevel level) {
        return source.getPermissions().hasPermission(new Permission.Level(level));
    }

    private static ServerPlayerEntity getPlayer(ServerCommandSource source) {
        return source.getPlayer();
    }
}
