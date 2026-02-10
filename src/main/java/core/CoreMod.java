package core;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import core.anticheat.AntiCheatManager;
import core.anticheat.AntiCheatCommands;
import core.bounty.BountyManager;
import core.bounty.BountyCommands;
import core.claims.ClaimManager;
import core.claims.ClaimCommands;
import core.clans.ClanManager;
import core.clans.ClanCommands;
import core.economy.EconomyManager;
import core.economy.AuctionManager;
import core.economy.AuctionCommands;
import core.economy.ShopManager;
import core.economy.EconomyCommands;
import core.voice.VoiceManager;
import core.trust.TrustManager;
import core.trust.TrustCommands;
import core.wanted.WantedManager;
import core.wanted.WantedCommands;
import core.logging.LoggingManager;
import core.discord.DiscordManager;
import core.map.MapManager;
import core.map.MapNetworking;
import core.map.MapCommands;
import core.config.ConfigManager;
import core.menu.MenuCommands;
import core.network.HandshakeServer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.network.ServerPlayerEntity;
import core.util.Safe;

public class CoreMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    @Override
    public void onInitialize() {
        Safe.run("HandshakeServer.init", HandshakeServer::init);

        try {
            ConfigManager.loadConfig();
            if (ConfigManager.getConfig().antiCheat.enableAntiCheat) {
                AntiCheatManager.init();
            }
            VoiceManager.init();
            ClaimManager.init();
            EconomyManager.init();
            AuctionManager.init();
            ShopManager.init();
            TrustManager.init();
            ClanManager.init();
            BountyManager.init();
            WantedManager.init();
            LoggingManager.init();
            DiscordManager.init();
            MapManager.init();
            MapNetworking.init();
        } catch (Exception e) {
            LOGGER.error("Error initializing Core Mod", e);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            Safe.run("CoreMod.commandRegistration", () -> {
                LOGGER.info("Starting command registration...");

            dispatcher.register(CommandManager.literal("core")
                .then(CommandManager.literal("help").executes(context -> {
                    context.getSource().sendMessage(Text.literal("§6Core Mod Help:"));
                    if (ConfigManager.getConfig().antiCheat.enableAntiCheat) {
                        context.getSource().sendMessage(Text.literal("§eAnti-Cheat Commands: /anticheat toggle, /anticheat check <player>"));
                    }
                    if (ConfigManager.getConfig().claim.enableClaims) {
                        context.getSource().sendMessage(Text.literal("§eClaim Commands: /claim, /unclaim, /claim info"));
                    }
                    context.getSource().sendMessage(Text.literal("§eEconomy Commands: /balance, /pay <player> <amount>, /shop"));
                    context.getSource().sendMessage(Text.literal("§eAuction Commands: /auction list, /auction sell <startingBid>, /auction bid <id> <amount>, /auction cancel <id>, /auction claim"));
                    context.getSource().sendMessage(Text.literal("§eTrust Commands: /trust <player>, /untrust <player>"));
                    context.getSource().sendMessage(Text.literal("§eClan Commands: /clan create <name>, /clan invite <player>, /clan leave"));
                    context.getSource().sendMessage(Text.literal("§eBounty Commands: /bounty set <player> <amount>, /bounty list"));
                    context.getSource().sendMessage(Text.literal("§eWanted Commands: /wanted add <player>, /wanted remove <player>"));
                    context.getSource().sendMessage(Text.literal("§eVoice Commands: /advancedgroups push <player> <group>, /advancedgroups release <player>"));
                    context.getSource().sendMessage(Text.literal("§eMap Commands: /minimap toggle, /map waypoint add <name> <x y z> <type> <visibility>, /map waypoint remove <name>, /map waypoint list, /map waypoint teleport <name>, /map waypoint share <name> <player>"));
                    context.getSource().sendMessage(Text.literal("§eAdmin Map Commands: /mapadmin listall, /mapadmin delete <name>, /mapadmin clear <player>"));
                    return 1;
                })));

            Safe.run("CoreMod.registerAdvancedGroups", () -> dispatcher.register(
                CommandManager.literal("advancedgroups")
                    .then(CommandManager.literal("push")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .then(CommandManager.argument("group", StringArgumentType.word())
                                .executes(context -> Safe.call("advancedgroups.push", () -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                    String group = StringArgumentType.getString(context, "group");
                                    VoiceManager.pushPlayer(target, group);
                                    context.getSource().sendMessage(Text.literal("Pushed " + target.getName().getString() + " to group " + group));
                                    return 1;
                                }, 0)))))
                    .then(CommandManager.literal("release")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(context -> Safe.call("advancedgroups.release", () -> {
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                VoiceManager.releasePlayer(target);
                                context.getSource().sendMessage(Text.literal("Released " + target.getName().getString() + " from group"));
                                return 1;
                            }, 0))))
            ));

            LOGGER.info("Registering EconomyCommands...");
            Safe.run("EconomyCommands.register", () -> EconomyCommands.register(dispatcher, registryAccess));

            LOGGER.info("Registering AuctionCommands...");
            Safe.run("AuctionCommands.register", () -> AuctionCommands.register(dispatcher));

            LOGGER.info("Registering MapCommands...");
            Safe.run("MapCommands.register", () -> MapCommands.register(dispatcher, registryAccess));

            LOGGER.info("Registering MenuCommands...");
            Safe.run("MenuCommands.register", () -> MenuCommands.register(dispatcher));

            if (ConfigManager.getConfig() != null && ConfigManager.getConfig().antiCheat.enableAntiCheat) {
                LOGGER.info("Registering AntiCheatCommands...");
                Safe.run("AntiCheatCommands.register", () -> AntiCheatCommands.register(dispatcher));
            }
            if (ConfigManager.getConfig() != null && ConfigManager.getConfig().claim.enableClaims) {
                LOGGER.info("Registering ClaimCommands...");
                Safe.run("ClaimCommands.register", () -> ClaimCommands.register(dispatcher));
            }
            LOGGER.info("Registering ClanCommands...");
            Safe.run("ClanCommands.register", () -> ClanCommands.register(dispatcher));
            LOGGER.info("Registering TrustCommands...");
            Safe.run("TrustCommands.register", () -> TrustCommands.register(dispatcher));
            LOGGER.info("Registering BountyCommands...");
            Safe.run("BountyCommands.register", () -> BountyCommands.register(dispatcher));
            LOGGER.info("Registering WantedCommands...");
            Safe.run("WantedCommands.register", () -> WantedCommands.register(dispatcher));
                LOGGER.info("Command registration completed.");
            }));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("CoreMod.onJoin", () ->
                handler.player.sendMessage(Text.literal("§6Welcome! Use /core help to see all available commands."), false)));
    }
}
