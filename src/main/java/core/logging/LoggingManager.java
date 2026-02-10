package core.logging;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
// import net.fabricmc.fabric.api.event.player.UseBlockCallback;
// import net.fabricmc.fabric.api.event.player.UseItemCallback;
// import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityCombatEvents;
// import net.fabricmc.fabric.api.event.player.ServerPlayerEvents;
// import net.fabricmc.fabric.api.event.advancement.ServerAdvancementEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
// import net.minecraft.util.ActionResult;
// import net.minecraft.util.TypedActionResult;
// import net.minecraft.util.hit.BlockHitResult;
// import net.minecraft.item.ItemStack;
import core.discord.DiscordManager;
import core.util.Safe;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LoggingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void init() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, player, params) ->
            Safe.run("LoggingManager.onChatMessage", () -> onChatMessage(message, player, params)));
        // ServerMessageEvents.COMMAND.register(LoggingManager::onCommandMessage); // Not available in this Fabric API version

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("LoggingManager.onJoin", () -> logEvent("JOIN", handler.player, null)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            Safe.run("LoggingManager.onDisconnect", () -> logEvent("LEAVE", handler.player, null)));
        // ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((server, attacker, target) -> {
        //     if (attacker instanceof ServerPlayerEntity player && target instanceof ServerPlayerEntity victim) {
        //         logEvent("KILL", player, victim.getName().getString() + " killed");
        //     }
        // });
        // ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> logEvent("RESPAWN", newPlayer, null));
        // ServerAdvancementEvents.ADVANCEMENT_GRANTED.register((player, advancement) -> logEvent("ADVANCEMENT", player, advancement.getDisplay().getTitle().getString()));
        // UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
        //     logEvent("BLOCK_INTERACT", (ServerPlayerEntity) player, hitResult.getBlockPos().toString());
        //     return ActionResult.PASS;
        // });
        // UseItemCallback.EVENT.register((player, world, hand) -> {
        //     ItemStack stack = player.getStackInHand(hand);
        //     logEvent("ITEM_USE", (ServerPlayerEntity) player, stack.getItem().getName().getString());
        //     return TypedActionResult.pass(stack);
        // });
    }

    private static void onChatMessage(SignedMessage message, ServerPlayerEntity player, Object params) {
        String msg = message.getContent().getString();
        logChatMessage(msg, player);
        if (msg.startsWith("/msg ") || msg.startsWith("/tell ") || msg.startsWith("/w ")) {
            logPrivateMessage(msg, player);
        }
    }

    private static void logPrivateMessage(String message, ServerPlayerEntity sender) {
        String log = "[" + LocalDateTime.now().format(FORMATTER) + "] " + sender.getName().getString() + ": " + message;
        try {
            Files.createDirectories(Paths.get("logs"));
            try (FileWriter writer = new FileWriter("logs/private-messages.log", true)) {
                writer.write(log + "\n");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to log private message", e);
        }
        Safe.run("DiscordManager.sendMessageLog", () -> DiscordManager.sendMessageLog(log));
    }

    private static void logChatMessage(String message, ServerPlayerEntity sender) {
        String log = "[" + LocalDateTime.now().format(FORMATTER) + "] " + sender.getName().getString() + ": " + message;
        try {
            Files.createDirectories(Paths.get("logs"));
            try (FileWriter writer = new FileWriter("logs/chat-messages.log", true)) {
                writer.write(log + "\n");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to log chat message", e);
        }
        Safe.run("DiscordManager.sendChatLog", () -> DiscordManager.sendChatLog(log));
    }

    private static void logCommand(String command, ServerPlayerEntity player) {
        String log = "[" + LocalDateTime.now().format(FORMATTER) + "] " + player.getName().getString() + " executed: " + command;
        try {
            Files.createDirectories(Paths.get("logs"));
            try (FileWriter writer = new FileWriter("logs/commands.log", true)) {
                writer.write(log + "\n");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to log command", e);
        }
        Safe.run("DiscordManager.sendCommandLog", () -> DiscordManager.sendCommandLog(log));
    }

    private static void logEvent(String type, ServerPlayerEntity player, String details) {
        String msg = "[" + LocalDateTime.now().format(FORMATTER) + "] " + type + ": " + player.getName().getString();
        if (details != null) {
            msg = msg + " - " + details;
        }
        final String msgFinal = msg;
        try {
            Files.createDirectories(Paths.get("logs"));
            try (FileWriter writer = new FileWriter("logs/events.log", true)) {
                writer.write(msgFinal + "\n");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to log event", e);
        }
        Safe.run("DiscordManager.sendOPLog", () -> DiscordManager.sendOPLog(msgFinal));
    }
}
