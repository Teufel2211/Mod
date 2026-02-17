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
import core.logging.ChestAuditManager;
import core.discord.DiscordManager;
import core.dashboard.DashboardManager;
import core.map.MapManager;
import core.map.MapNetworking;
import core.map.MapCommands;
import core.announcement.AnnouncementCommands;
import core.announcement.AnnouncementManager;
import core.config.ConfigManager;
import core.config.ConfigCommands;
import core.menu.MenuCommands;
import core.network.HandshakeServer;
import core.world.WorldCommands;
import core.moderation.ModerationManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.network.ServerPlayerEntity;
import core.util.Safe;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import java.util.List;

public class CoreMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final SuggestionProvider<net.minecraft.server.command.ServerCommandSource> VOICE_GROUP_SUGGESTIONS =
        (context, builder) -> CommandSource.suggestMatching(
            List.of("global", "staff", "support", "admin", "mod", "builder", "vip"),
            builder
        );

    @Override
    public void onInitialize() {
        ConfigManager.loadConfig();
        var cfg = ConfigManager.getConfig();
        var systems = cfg.systems;

        if (systems.handshake) {
            Safe.run("HandshakeServer.init", HandshakeServer::init);
        }

        try {
            if (systems.antiCheat && cfg.antiCheat.enableAntiCheat) {
                AntiCheatManager.init();
            }
            if (systems.voice) VoiceManager.init();
            if (systems.claims && cfg.claim.enableClaims) ClaimManager.init();
            if (systems.economy) EconomyManager.init();
            if (systems.auction) AuctionManager.init();
            if (systems.shop) ShopManager.init();
            if (systems.trust) TrustManager.init();
            if (systems.clans) ClanManager.init();
            if (systems.bounty) BountyManager.init();
            if (systems.wanted) WantedManager.init();
            if (systems.logging) LoggingManager.init();
            if (systems.chestAudit) ChestAuditManager.init();
            if (systems.discord) DiscordManager.init();
            if (systems.dashboard) DashboardManager.init();
            if (systems.map) MapManager.init();
            if (systems.mapNetworking) MapNetworking.init();
            if (systems.announcements) AnnouncementManager.init();
            Safe.run("ModerationManager.init", ModerationManager::init);
        } catch (Exception e) {
            LOGGER.error("Error initializing Core Mod", e);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            Safe.run("CoreMod.commandRegistration", () -> {
                LOGGER.info("Starting command registration...");

            dispatcher.register(CommandManager.literal("core")
                .then(CommandManager.literal("help").executes(context -> {
                    context.getSource().sendMessage(Text.literal("§6Core Mod Help:"));
                    if (systems.antiCheat && cfg.antiCheat.enableAntiCheat) {
                        context.getSource().sendMessage(Text.literal("§eAnti-Cheat Commands: /anticheat toggle, /anticheat check <player>"));
                    }
                    if (systems.claims && cfg.claim.enableClaims) {
                        context.getSource().sendMessage(Text.literal("§eClaim Commands: /claim, /unclaim, /claim info"));
                    }
                    if (systems.economy || systems.shop) {
                        context.getSource().sendMessage(Text.literal("§eEconomy Commands: /balance, /pay <player> <amount>, /shop"));
                        context.getSource().sendMessage(Text.literal("§eSell Command: /sell (opens sell GUI)"));
                    }
                    if (systems.auction) {
                        context.getSource().sendMessage(Text.literal("§eAuction Commands: /auction list, /auction sell <startingBid>, /auction bid <id> <amount>, /auction cancel <id>, /auction claim"));
                    }
                    if (systems.trust) context.getSource().sendMessage(Text.literal("§eTrust Commands: /trust <player>, /untrust <player>"));
                    if (systems.clans) context.getSource().sendMessage(Text.literal("§eClan Commands: /clan create <name>, /clan invite <player>, /clan leave"));
                    if (systems.bounty) context.getSource().sendMessage(Text.literal("§eBounty Commands: /bounty set <player> <amount>, /bounty list"));
                    if (systems.wanted) context.getSource().sendMessage(Text.literal("§eWanted Commands: /wanted add <player>, /wanted remove <player>"));
                    if (systems.voice) context.getSource().sendMessage(Text.literal("§eVoice Commands: /advancedgroups push <player> <group>, /advancedgroups release <player>"));
                    if (systems.map) {
                        context.getSource().sendMessage(Text.literal("§eMap Commands: /minimap toggle, /map waypoint add <name> <x y z> <type> <visibility>, /map waypoint remove <name>, /map waypoint list, /map waypoint teleport <name>, /map waypoint share <name> <player>"));
                        context.getSource().sendMessage(Text.literal("§eAdmin Map Commands: /mapadmin listall, /mapadmin delete <name>, /mapadmin clear <player>"));
                    }
                    if (systems.worldCommands) context.getSource().sendMessage(Text.literal("§eWorld Commands: /spawn, /rtp [radius]"));
                    if (systems.announcements) context.getSource().sendMessage(Text.literal("§eAnnouncement Commands: /announce chat <msg>, /announce actionbar <msg>, /announce both <msg>, /announce player <player> <msg>, /announce reload"));
                    context.getSource().sendMessage(Text.literal("§eConfig Commands: /coresettings list, /coresettings export <name>, /coresettings import <name>, /coresettings exportworld <name> <world>"));
                    return 1;
                })));

            if (systems.voice) {
                Safe.run("CoreMod.registerAdvancedGroups", () -> dispatcher.register(
                    CommandManager.literal("advancedgroups")
                        .then(CommandManager.literal("push")
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("group", StringArgumentType.word())
                                    .suggests(VOICE_GROUP_SUGGESTIONS)
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
            }

            if (systems.economy || systems.shop) {
                LOGGER.info("Registering EconomyCommands...");
                Safe.run("EconomyCommands.register", () -> EconomyCommands.register(dispatcher, registryAccess));
            }

            if (systems.auction) {
                LOGGER.info("Registering AuctionCommands...");
                Safe.run("AuctionCommands.register", () -> AuctionCommands.register(dispatcher));
            }

            if (systems.map) {
                LOGGER.info("Registering MapCommands...");
                Safe.run("MapCommands.register", () -> MapCommands.register(dispatcher, registryAccess));
            }

            if (systems.menus) {
                LOGGER.info("Registering MenuCommands...");
                Safe.run("MenuCommands.register", () -> MenuCommands.register(dispatcher));
            }

            if (systems.worldCommands) {
                LOGGER.info("Registering WorldCommands...");
                Safe.run("WorldCommands.register", () -> WorldCommands.register(dispatcher));
            }
            if (systems.announcements) {
                LOGGER.info("Registering AnnouncementCommands...");
                Safe.run("AnnouncementCommands.register", () -> AnnouncementCommands.register(dispatcher));
            }
            LOGGER.info("Registering ConfigCommands...");
            Safe.run("ConfigCommands.register", () -> ConfigCommands.register(dispatcher));

            if (systems.antiCheat && cfg.antiCheat.enableAntiCheat) {
                LOGGER.info("Registering AntiCheatCommands...");
                Safe.run("AntiCheatCommands.register", () -> AntiCheatCommands.register(dispatcher));
            }
            if (systems.claims && cfg.claim.enableClaims) {
                LOGGER.info("Registering ClaimCommands...");
                Safe.run("ClaimCommands.register", () -> ClaimCommands.register(dispatcher));
            }
            if (systems.clans) {
                LOGGER.info("Registering ClanCommands...");
                Safe.run("ClanCommands.register", () -> ClanCommands.register(dispatcher));
            }
            if (systems.trust) {
                LOGGER.info("Registering TrustCommands...");
                Safe.run("TrustCommands.register", () -> TrustCommands.register(dispatcher));
            }
            if (systems.bounty) {
                LOGGER.info("Registering BountyCommands...");
                Safe.run("BountyCommands.register", () -> BountyCommands.register(dispatcher));
            }
            if (systems.wanted) {
                LOGGER.info("Registering WantedCommands...");
                Safe.run("WantedCommands.register", () -> WantedCommands.register(dispatcher));
            }
                LOGGER.info("Command registration completed.");
            }));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("CoreMod.onJoin", () ->
                handler.player.sendMessage(Text.literal("§6Welcome! Use /core help to see all available commands."), false)));
    }
}
