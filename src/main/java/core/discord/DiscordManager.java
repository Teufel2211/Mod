package core.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import core.config.ConfigManager;
import core.bounty.BountyManager;
import core.economy.EconomyManager;
import core.anticheat.AntiCheatManager;
import core.logging.LoggingManager;
import core.claims.ClaimManager;
import core.clans.ClanManager;
import core.moderation.ModerationManager;
import core.util.Safe;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-discord-logger");
        t.setDaemon(true);
        return t;
    });
    private static final File LINKED_ACCOUNTS_FILE = new File("data/core-discord-links.json");
    private static final int DISCORD_MAX_EMBED_DESCRIPTION = 3500;
    private static final int WEBHOOK_MAX_RETRIES = 3;
    private static final long WEBHOOK_RETRY_DELAY_MS = 400L;
    private static final long DEDUPE_WINDOW_MS = 1500L;
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern EVENT_LINE_PATTERN = Pattern.compile("^\\[(?<time>[^\\]]+)]\\s*(?<type>[A-Z_]+):\\s*(?<player>[^\\-]+?)(?:\\s*-\\s*(?<details>.*))?$");
    private static final Pattern COMMAND_LINE_PATTERN = Pattern.compile("^\\[(?<time>[^\\]]+)]\\s*(?<player>.+?)\\s+executed:\\s*(?<command>.+)$");
    private static final Pattern CHAT_LINE_PATTERN = Pattern.compile("^\\[(?<time>[^\\]]+)]\\s*(?<player>[^:]+):\\s*(?<message>.*)$");
    private static final List<String> FIELD_ORDER = List.of("Time", "Type", "Player", "Command", "Details", "Message", "Reason", "Amount", "Action", "Target");

    private static JDA jda;
    private static volatile boolean botStarted = false;
    private static final Map<String, UUID> discordToMinecraft = new ConcurrentHashMap<>();
    private static final Map<UUID, String> minecraftToDiscord = new ConcurrentHashMap<>();
    private static final Map<String, Long> webhookDedupe = new ConcurrentHashMap<>();
    private static final Map<String, Long> webhookRateLimitUntil = new ConcurrentHashMap<>();
    private static MinecraftServer server;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> Safe.run("DiscordManager.onServerStarted", () -> {
            server = s;
            startBotIfConfigured();
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("DiscordManager.shutdown", DiscordManager::shutdown));
        Safe.run("DiscordManager.loadLinkedAccounts", DiscordManager::loadLinkedAccounts);
    }

    private static void startBotIfConfigured() {
        if (botStarted) return;
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.discord == null) return;

        String token = cfg.discord.botToken;
        if (token == null || token.isBlank()) {
            LOGGER.info("Discord bot disabled: botToken is empty.");
            return;
        }

        botStarted = true;
        EXECUTOR.submit(() -> {
            try {
                jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new DiscordCommandListener())
                    .build();

                jda.awaitReady();

                if (cfg.discord.enableBidirectionalCommands) {
                    registerSlashCommands();
                }

                LOGGER.info("Discord bot started with server.");
            } catch (Exception e) {
                botStarted = false;
                LOGGER.error("Failed to initialize Discord bot", e);
            }
        });
    }

    private static void registerSlashCommands() {
        if (jda == null) return;

        Guild guild = jda.getGuildById(ConfigManager.getConfig().discord.serverId);
        if (guild == null) return;

        guild.updateCommands().addCommands(
            Commands.slash("ban", "Ban a player")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.STRING, "reason", "Ban reason", false),
            Commands.slash("eco", "Economy commands")
                .addOption(OptionType.STRING, "action", "Action (set/add/remove)", true)
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.NUMBER, "amount", "Amount", true),
            Commands.slash("bounty", "Bounty commands")
                .addOption(OptionType.STRING, "action", "Action (place/list)", true)
                .addOption(OptionType.STRING, "player", "Target player", false)
                .addOption(OptionType.NUMBER, "amount", "Bounty amount", false),
            Commands.slash("status", "Show live server status"),
            Commands.slash("players", "Show currently online players"),
            Commands.slash("announce", "Send in-game announcement")
                .addOption(OptionType.STRING, "message", "Announcement text", true)
                .addOption(OptionType.STRING, "mode", "chat/actionbar/both", false),
            Commands.slash("servercmd", "Execute allowed server command")
                .addOption(OptionType.STRING, "command", "Command without leading slash", true),
            Commands.slash("events", "Show latest server events")
                .addOption(OptionType.INTEGER, "limit", "How many (1-15)", false)
                .addOption(OptionType.STRING, "channel", "all/event/chat/command/private/game", false),
            Commands.slash("topcommands", "Top used commands from recent logs"),
            Commands.slash("playerinfo", "Show details for an online player")
                .addOption(OptionType.STRING, "name", "Exact player name", true),
            Commands.slash("inventory", "Show online player's inventory summary")
                .addOption(OptionType.STRING, "name", "Exact player name", true),
            Commands.slash("enderchest", "Show online player's ender chest summary")
                .addOption(OptionType.STRING, "name", "Exact player name", true),
            Commands.slash("kick", "Kick a player")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.STRING, "reason", "Reason", false),
            Commands.slash("gamemode", "Set player's gamemode")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.STRING, "mode", "survival/creative/adventure/spectator", true),
            Commands.slash("freeze", "Freeze player via anti-cheat")
                .addOption(OptionType.STRING, "player", "Player name", true),
            Commands.slash("unfreeze", "Unfreeze player via anti-cheat")
                .addOption(OptionType.STRING, "player", "Player name", true),
            Commands.slash("setbalance", "Set player coin balance")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.NUMBER, "amount", "New balance", true),
            Commands.slash("whitelist", "Whitelist on/off/add/remove")
                .addOption(OptionType.STRING, "action", "on/off/add/remove", true)
                .addOption(OptionType.STRING, "player", "Player name for add/remove", false),
            Commands.slash("serveroverview", "High-level admin overview")
            ,
            Commands.slash("saveall", "Run save-all on the server"),
            Commands.slash("op", "OP a player")
                .addOption(OptionType.STRING, "player", "Player name", true),
            Commands.slash("deop", "De-OP a player")
                .addOption(OptionType.STRING, "player", "Player name", true),
            Commands.slash("pardon", "Unban a player")
                .addOption(OptionType.STRING, "player", "Player name", true)
            ,
            Commands.slash("economytop", "Top coin balances"),
            Commands.slash("economy", "Economy info for an online player")
                .addOption(OptionType.STRING, "player", "Player name", true),
            Commands.slash("claims", "Claims overview (count + sample)"),
            Commands.slash("clans", "Clans overview (count + sample)"),
            Commands.slash("note", "Add moderation note to online player")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.STRING, "text", "Note text", true),
            Commands.slash("warn", "Add warning to online player")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.STRING, "reason", "Warn reason", true)
            ,
            Commands.slash("mute", "Mute an online player (blocks chat)")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.INTEGER, "minutes", "Duration in minutes (omit/0 = permanent)", false)
                .addOption(OptionType.STRING, "reason", "Reason", false),
            Commands.slash("unmute", "Unmute an online player")
                .addOption(OptionType.STRING, "player", "Player name", true)
        ).queue(
            ignored -> LOGGER.info("Discord slash commands registered"),
            error -> LOGGER.warn("Failed to register discord slash commands", error)
        );
    }

    // Account Linking
    public static boolean linkAccount(String discordId, UUID minecraftUuid) {
        if (discordToMinecraft.containsKey(discordId) || minecraftToDiscord.containsKey(minecraftUuid)) {
            return false; // Already linked
        }

        discordToMinecraft.put(discordId, minecraftUuid);
        minecraftToDiscord.put(minecraftUuid, discordId);
        saveLinkedAccounts();
        return true;
    }

    public static boolean unlinkAccount(String discordId, UUID minecraftUuid) {
        if (!discordToMinecraft.getOrDefault(discordId, UUID.randomUUID()).equals(minecraftUuid)) {
            return false;
        }

        discordToMinecraft.remove(discordId);
        minecraftToDiscord.remove(minecraftUuid);
        saveLinkedAccounts();
        return true;
    }

    public static UUID getMinecraftUuid(String discordId) {
        return discordToMinecraft.get(discordId);
    }

    public static String getDiscordId(UUID minecraftUuid) {
        return minecraftToDiscord.get(minecraftUuid);
    }

    public static boolean isLinked(String discordId) {
        return discordToMinecraft.containsKey(discordId);
    }

    public static boolean isLinked(UUID minecraftUuid) {
        return minecraftToDiscord.containsKey(minecraftUuid);
    }

    // Enhanced Webhook Logging
    public static void sendEconomyLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.economyLogWebhook, "💰 Economy Transaction", message, 0x00FF00);
    }

    public static void sendAdminActionLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.adminActionsWebhook, "👑 Admin Action", message, 0xFF0000);
    }

    public static void sendBountyFeed(String message) {
        sendWebhook(ConfigManager.getConfig().discord.bountyFeedWebhook, "🎯 Bounty Update", message, 0xFFA500);
    }

    public static void sendAnticheatAlert(String message, boolean critical) {
        int color = critical ? 0xFF0000 : 0xFFFF00;
        String title = critical ? "🚨 CRITICAL Anti-Cheat Alert" : "⚠️ Anti-Cheat Alert";
        sendWebhook(ConfigManager.getConfig().discord.anticheatAlertsWebhook, title, message, color);
    }

    public static void sendClanEvent(String message) {
        sendWebhook(ConfigManager.getConfig().discord.clanEventsWebhook, "🏰 Clan Event", message, 0x800080);
    }

    public static void sendPlayerReport(String message) {
        sendWebhook(ConfigManager.getConfig().discord.playerReportsWebhook, "📝 Player Report", message, 0xFF69B4);
    }

    // Legacy methods for backward compatibility
    public static void sendOPLog(String message) {
        sendAdminActionLog(message);
    }

    public static void sendMessageLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.messageLogWebhook, "💬 Private Message", message, 0x0080FF);
    }

    public static void sendChatLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.messageLogWebhook, "🗣️ Public Chat", message, 0x0080FF);
    }

    public static void sendCommandLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.commandLogWebhook, "⚡ Command Executed", message, 0x808080);
    }

    private static void sendWebhook(String url, String title, String description, int color) {
        String webhookUrl = resolveWebhook(url);
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        EXECUTOR.submit(() -> {
            String safeDescription = sanitizeDiscordText(description);
            if (isDuplicate(webhookUrl, title, safeDescription)) {
                return;
            }
            List<Field> fields = extractFields(safeDescription);
            String embedDescription = fields.isEmpty()
                ? formatDescription(safeDescription)
                : "Details";
            Embed embed = new Embed(title, embedDescription, color, fields);
            String json = GSON.toJson(new WebhookPayload(embed));
            RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
            Request request = new Request.Builder()
                .url(webhookUrl)
                .header("User-Agent", "core-mod-discord-logger")
                .post(body)
                .build();

            for (int attempt = 1; attempt <= WEBHOOK_MAX_RETRIES; attempt++) {
                long now = System.currentTimeMillis();
                long blockedUntil = webhookRateLimitUntil.getOrDefault(webhookUrl, 0L);
                if (blockedUntil > now) {
                    long waitMs = blockedUntil - now;
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                try (Response response = CLIENT.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        return;
                    }

                    if (response.code() == 429) {
                        String bodyText = "";
                        try {
                            if (response.body() != null) {
                                bodyText = response.body().string();
                            }
                        } catch (IOException ignored) {
                        }
                        long retryMs = parseRetryDelayMs(response.header("Retry-After"), bodyText);
                        long until = System.currentTimeMillis() + retryMs;
                        webhookRateLimitUntil.put(webhookUrl, until);
                        LOGGER.warn("Discord webhook rate-limited (HTTP 429). Waiting {} ms before retry.", retryMs);
                        try {
                            Thread.sleep(retryMs);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }

                    LOGGER.warn("Discord webhook failed (attempt {}): HTTP {}", attempt, response.code());
                } catch (IOException e) {
                    LOGGER.warn("Discord webhook IO error (attempt {})", attempt, e);
                }

                if (attempt < WEBHOOK_MAX_RETRIES) {
                    try {
                        Thread.sleep(WEBHOOK_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    private static long parseRetryDelayMs(String retryAfterHeader, String bodyText) {
        long fallback = 1500L;
        try {
            if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
                double value = Double.parseDouble(retryAfterHeader.trim());
                if (value > 0) {
                    // Discord may return seconds in header. Convert to ms.
                    long ms = (long) Math.ceil(value * 1000.0);
                    return Math.max(250L, ms);
                }
            }
        } catch (NumberFormatException ignored) {
        }

        if (bodyText != null && !bodyText.isBlank()) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(bodyText);
            if (m.find()) {
                try {
                    double value = Double.parseDouble(m.group(1));
                    // Discord JSON retry_after is usually in ms for webhook rate limits.
                    long ms = (long) Math.ceil(value);
                    if (ms <= 0) return fallback;
                    if (ms < 250) ms = 250;
                    return ms;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return fallback;
    }

    private static boolean isDuplicate(String webhookUrl, String title, String description) {
        String key = webhookUrl + "|" + title + "|" + description;
        long now = System.currentTimeMillis();
        Long prev = webhookDedupe.put(key, now);
        if (prev == null) return false;
        return now - prev < DEDUPE_WINDOW_MS;
    }

    private static String formatDescription(String raw) {
        if (raw == null || raw.isBlank()) return "(empty)";
        String[] lines = raw.split("\\R");
        if (lines.length <= 1) return raw;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.isBlank()) {
                sb.append("• ").append(line.trim()).append('\n');
            }
        }
        String formatted = sb.toString().trim();
        if (formatted.isEmpty()) return raw;
        return formatted;
    }

    private static List<Field> extractFields(String raw) {
        List<Field> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;

        String firstLine = raw.split("\\R", 2)[0].trim();
        Matcher eventMatcher = EVENT_LINE_PATTERN.matcher(firstLine);
        if (eventMatcher.matches()) {
            addField(out, "Time", eventMatcher.group("time"));
            addField(out, "Type", eventMatcher.group("type"));
            addField(out, "Player", eventMatcher.group("player"));
            addField(out, "Details", eventMatcher.group("details"));
            return out;
        }

        Matcher commandMatcher = COMMAND_LINE_PATTERN.matcher(firstLine);
        if (commandMatcher.matches()) {
            addField(out, "Time", commandMatcher.group("time"));
            addField(out, "Player", commandMatcher.group("player"));
            addField(out, "Command", commandMatcher.group("command"));
            return out;
        }

        Matcher chatMatcher = CHAT_LINE_PATTERN.matcher(firstLine);
        if (chatMatcher.matches()) {
            addField(out, "Time", chatMatcher.group("time"));
            addField(out, "Player", chatMatcher.group("player"));
            addField(out, "Message", chatMatcher.group("message"));
            return out;
        }

        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0 || idx >= trimmed.length() - 1) continue;
            String name = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (name.length() > 64) name = name.substring(0, 64);
            if (value.length() > 256) value = value.substring(0, 256);
            if (!name.isEmpty() && !value.isEmpty()) {
                out.add(new Field(name, value, true));
            }
            if (out.size() >= 8) break;
        }
        out.sort((a, b) -> {
            int ia = fieldIndex(a.name);
            int ib = fieldIndex(b.name);
            if (ia != ib) return Integer.compare(ia, ib);
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    private static int fieldIndex(String name) {
        if (name == null) return Integer.MAX_VALUE;
        int idx = FIELD_ORDER.indexOf(name);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    private static void addField(List<Field> out, String name, String value) {
        if (value == null) return;
        String n = name.trim();
        String v = value.trim();
        if (n.isEmpty() || v.isEmpty()) return;
        if (n.length() > 64) n = n.substring(0, 64);
        if (v.length() > 256) v = v.substring(0, 256);
        out.add(new Field(n, v, true));
    }

    private static String resolveWebhook(String specificWebhook) {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.discord == null) return null;
        if (specificWebhook != null && !specificWebhook.isBlank()) return specificWebhook;
        return cfg.discord.webhookUrl;
    }

    private static String sanitizeDiscordText(String raw) {
        if (raw == null) return "";
        String sanitized = raw
            .replace("@everyone", "@\u200beveryone")
            .replace("@here", "@\u200bhere");
        if (sanitized.length() > DISCORD_MAX_EMBED_DESCRIPTION) {
            return sanitized.substring(0, DISCORD_MAX_EMBED_DESCRIPTION - 3) + "...";
        }
        return sanitized;
    }

    private static void loadLinkedAccounts() {
        if (LINKED_ACCOUNTS_FILE.exists()) {
            try (FileReader reader = new FileReader(LINKED_ACCOUNTS_FILE)) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> data = GSON.fromJson(reader, type);
                if (data != null) {
                    for (Map.Entry<String, String> entry : data.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            String discordId = entry.getValue();
                            discordToMinecraft.put(discordId, uuid);
                            minecraftToDiscord.put(uuid, discordId);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Invalid UUID in linked accounts: " + entry.getKey());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load linked accounts", e);
            }
        }
    }

    private static void saveLinkedAccounts() {
        try {
            LINKED_ACCOUNTS_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(LINKED_ACCOUNTS_FILE)) {
                Map<String, String> data = new HashMap<>();
                for (Map.Entry<UUID, String> entry : minecraftToDiscord.entrySet()) {
                    data.put(entry.getKey().toString(), entry.getValue());
                }
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save linked accounts", e);
        }
    }

    // Discord Command Listener
    private static class DiscordCommandListener extends ListenerAdapter {
        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            var cfg = ConfigManager.getConfig();
            if (cfg == null || cfg.discord == null || !cfg.discord.enableBidirectionalCommands) return;

            Member member = event.getMember();
            if (member == null) return;
            String command = event.getName();

            if (!hasCommandPermission(member, command, cfg)) {
                event.reply("❌ You don't have permission to use this command category.").setEphemeral(true).queue();
                return;
            }

            switch (command) {
                case "ban" -> handleBanCommand(event);
                case "eco" -> handleEcoCommand(event);
                case "bounty" -> handleBountyCommand(event);
                case "status" -> handleStatusCommand(event);
                case "players" -> handlePlayersCommand(event);
                case "announce" -> handleAnnounceCommand(event);
                case "servercmd" -> handleServerCmdCommand(event);
                case "events" -> handleEventsCommand(event);
                case "topcommands" -> handleTopCommandsCommand(event);
                case "playerinfo" -> handlePlayerInfoCommand(event);
                case "inventory" -> handleInventoryCommand(event);
                case "enderchest" -> handleEnderChestCommand(event);
                case "kick" -> handleKickCommand(event);
                case "gamemode" -> handleGamemodeCommand(event);
                case "freeze" -> handleFreezeCommand(event);
                case "unfreeze" -> handleUnfreezeCommand(event);
                case "setbalance" -> handleSetBalanceCommand(event);
                case "whitelist" -> handleWhitelistCommand(event);
                case "serveroverview" -> handleServerOverviewCommand(event);
                case "saveall" -> handleSaveAllCommand(event);
                case "op" -> handleOpCommand(event);
                case "deop" -> handleDeopCommand(event);
                case "pardon" -> handlePardonCommand(event);
                case "economytop" -> handleEconomyTopCommand(event);
                case "economy" -> handleEconomyPlayerCommand(event);
                case "claims" -> handleClaimsOverviewCommand(event);
                case "clans" -> handleClansOverviewCommand(event);
                case "note" -> handleNoteCommand(event);
                case "warn" -> handleWarnCommand(event);
                case "mute" -> handleMuteCommand(event);
                case "unmute" -> handleUnmuteCommand(event);
            }
        }

        private boolean hasCommandPermission(Member member, String command, ConfigManager.Config cfg) {
            if (member == null || command == null || cfg == null || cfg.discord == null) return false;
            var dc = cfg.discord;
            if (!dc.enableRoleBasedPermissions) {
                return hasAnyRole(member, dc.allowedDiscordRoles);
            }

            String[] fallback = dc.allowedDiscordRoles;
            if (isSensitiveReadCommand(command)) {
                return hasAnyRole(member, chooseSpecificOrFallback(dc.sensitiveDiscordRoles, fallback));
            }
            if (isControlCommand(command)) {
                return hasAnyRole(member, chooseSpecificOrFallback(dc.controlDiscordRoles, fallback));
            }
            if (isModerationCommand(command)) {
                return hasAnyRole(member, chooseSpecificOrFallback(dc.moderationDiscordRoles, fallback));
            }
            if (isEconomyWriteCommand(command)) {
                return hasAnyRole(member, chooseSpecificOrFallback(dc.economyDiscordRoles, fallback));
            }
            return hasAnyRole(member, chooseSpecificOrFallback(dc.readDiscordRoles, fallback));
        }

        private String[] chooseSpecificOrFallback(String[] specific, String[] fallback) {
            if (specific != null && specific.length > 0) return specific;
            return fallback;
        }

        private boolean hasAnyRole(Member member, String[] roleIds) {
            if (member == null) return false;
            if (roleIds == null || roleIds.length == 0) return true;
            return member.getRoles().stream().anyMatch(role ->
                Arrays.stream(roleIds).anyMatch(id -> id != null && !id.isBlank() && id.trim().equals(role.getId())));
        }

        private boolean isSensitiveReadCommand(String command) {
            return "inventory".equals(command) || "enderchest".equals(command);
        }

        private boolean isEconomyWriteCommand(String command) {
            return "eco".equals(command) || "setbalance".equals(command) || "bounty".equals(command);
        }

        private boolean isModerationCommand(String command) {
            return "ban".equals(command) || "kick".equals(command) || "freeze".equals(command) || "unfreeze".equals(command)
                || "warn".equals(command) || "mute".equals(command) || "unmute".equals(command) || "pardon".equals(command)
                || "gamemode".equals(command) || "note".equals(command) || "whitelist".equals(command);
        }

        private boolean isControlCommand(String command) {
            return "servercmd".equals(command) || "saveall".equals(command) || "announce".equals(command)
                || "op".equals(command) || "deop".equals(command);
        }

        private void handleBanCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("player") == null) {
                event.reply("Missing required option: player").setEphemeral(true).queue();
                return;
            }
            String playerName = event.getOption("player").getAsString();
            String reason = event.getOption("reason") != null ? event.getOption("reason").getAsString() : "No reason provided";

            if (server != null) {
                server.execute(() ->
                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "ban " + playerName + " " + reason));
            }

            event.reply("🔨 Banned player: " + playerName + "\nReason: " + reason).queue();

            sendAdminActionLog("Discord ban executed by " + event.getUser().getEffectiveName() +
                "\nPlayer: " + playerName + "\nReason: " + reason);
        }

        private void handleEcoCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("action") == null || event.getOption("player") == null || event.getOption("amount") == null) {
                event.reply("Missing required options for /eco").setEphemeral(true).queue();
                return;
            }
            String action = event.getOption("action").getAsString();
            String playerName = event.getOption("player").getAsString();
            double amount = event.getOption("amount").getAsDouble();

            if (server != null) {
                server.execute(() ->
                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "eco " + action + " " + playerName + " " + amount));
            }

            event.reply("💰 " + action + " " + amount + " to/from " + playerName).queue();

            sendEconomyLog("Discord economy command by " + event.getUser().getEffectiveName() +
                "\nAction: " + action + "\nPlayer: " + playerName + "\nAmount: " + amount);
        }

        private void handleBountyCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("action") == null) {
                event.reply("Missing required option: action").setEphemeral(true).queue();
                return;
            }
            String action = event.getOption("action").getAsString();

            if ("place".equals(action)) {
                if (event.getOption("player") == null || event.getOption("amount") == null) {
                    event.reply("Missing required options for bounty placement").setEphemeral(true).queue();
                    return;
                }
                String playerName = event.getOption("player").getAsString();
                double amount = event.getOption("amount").getAsDouble();

                if (server != null) {
                    server.execute(() ->
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "bounty set " + playerName + " " + amount));
                }

                event.reply("🎯 Placed bounty of " + amount + " on " + playerName).queue();

                sendBountyFeed("Discord bounty placed by " + event.getUser().getEffectiveName() +
                    "\nTarget: " + playerName + "\nAmount: " + amount);
            } else if ("list".equals(action)) {
                String list = getBountyList();
                event.reply(list).queue();
            } else {
                event.reply("Unknown action. Use: place or list").setEphemeral(true).queue();
            }
        }

        private void handleStatusCommand(SlashCommandInteractionEvent event) {
            if (server == null) {
                event.reply("Server unavailable").setEphemeral(true).queue();
                return;
            }
            event.reply("Online: " + server.getCurrentPlayerCount() + "/" + server.getMaxPlayerCount()
                + "\nVersion: " + server.getVersion()
                + "\nMOTD: " + server.getServerMotd()).queue();
        }

        private void handlePlayersCommand(SlashCommandInteractionEvent event) {
            if (server == null) {
                event.reply("Server unavailable").setEphemeral(true).queue();
                return;
            }
            var players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                event.reply("No players online.").queue();
                return;
            }
            String names = players.stream().map(p -> p.getName().getString()).sorted(String::compareToIgnoreCase).limit(25).reduce((a, b) -> a + ", " + b).orElse("-");
            event.reply("Online players (" + players.size() + "): " + names).queue();
        }

        private void handleAnnounceCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("message") == null) {
                event.reply("Missing message").setEphemeral(true).queue();
                return;
            }
            String message = event.getOption("message").getAsString().replace('\n', ' ').trim();
            if (message.isBlank()) {
                event.reply("Message cannot be empty").setEphemeral(true).queue();
                return;
            }
            String mode = event.getOption("mode") == null ? "chat" : event.getOption("mode").getAsString().trim().toLowerCase();
            if (!mode.equals("chat") && !mode.equals("actionbar") && !mode.equals("both")) {
                event.reply("Mode must be chat, actionbar, or both").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                String cmd = "announce " + mode + " " + message;
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Announcement sent (" + mode + ").").queue();
            sendAdminActionLog("Discord announce by " + event.getUser().getEffectiveName() + "\nMode: " + mode + "\nMessage: " + message);
        }

        private void handleServerCmdCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote server commands are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("command") == null) {
                event.reply("Missing command").setEphemeral(true).queue();
                return;
            }
            String cmd = event.getOption("command").getAsString().trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.isBlank()) {
                event.reply("Command cannot be empty").setEphemeral(true).queue();
                return;
            }
            if (!isAllowedRemoteCommand(cmd)) {
                event.reply("Command is not allowed by prefix policy.").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                String finalCmd = cmd;
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), finalCmd));
            }
            event.reply("Executed: /" + cmd).queue();
            sendAdminActionLog("Discord servercmd by " + event.getUser().getEffectiveName() + "\nCommand: /" + cmd);
        }

        private void handleEventsCommand(SlashCommandInteractionEvent event) {
            int limit = event.getOption("limit") == null ? 8 : (int) event.getOption("limit").getAsLong();
            limit = Math.max(1, Math.min(15, limit));
            String channel = event.getOption("channel") == null ? "all" : event.getOption("channel").getAsString().trim().toLowerCase();
            List<LoggingManager.RecentLogEntry> events = LoggingManager.getRecentLogs(150).stream()
                .filter(e -> "all".equals(channel) || e.channel.equalsIgnoreCase(channel))
                .limit(limit)
                .toList();
            if (events.isEmpty()) {
                event.reply("No events found for channel `" + channel + "`.").setEphemeral(true).queue();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (var e : events) {
                String msg = e.message.length() > 140 ? e.message.substring(0, 140) + "..." : e.message;
                sb.append("`").append(e.channel).append("` ").append(msg).append("\n");
            }
            event.reply(sb.toString().trim()).setEphemeral(true).queue();
        }

        private void handleTopCommandsCommand(SlashCommandInteractionEvent event) {
            Map<String, Integer> counts = new HashMap<>();
            for (var e : LoggingManager.getRecentLogs(300)) {
                if (!"command".equalsIgnoreCase(e.channel)) continue;
                int idx = e.message.indexOf(" executed: ");
                if (idx < 0) continue;
                String cmd = e.message.substring(idx + " executed: ".length()).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isBlank()) continue;
                String root = cmd.split("\\s+")[0].toLowerCase();
                counts.merge(root, 1, Integer::sum);
            }
            if (counts.isEmpty()) {
                event.reply("No command activity in recent logs.").setEphemeral(true).queue();
                return;
            }
            StringBuilder sb = new StringBuilder("Top commands (recent):\n");
            counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> sb.append("• `/").append(entry.getKey()).append("` -> ").append(entry.getValue()).append("\n"));
            event.reply(sb.toString().trim()).setEphemeral(true).queue();
        }

        private void handlePlayerInfoCommand(SlashCommandInteractionEvent event) {
            if (server == null) {
                event.reply("Server unavailable").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("name") == null) {
                event.reply("Missing name").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("name").getAsString().trim();
            var player = server.getPlayerManager().getPlayer(name);
            if (player == null) {
                event.reply("Player `" + name + "` is offline or not found.").setEphemeral(true).queue();
                return;
            }
            String world = player.getEntityWorld().getRegistryKey().getValue().toString();
            String info = "Player: `" + player.getName().getString() + "`\n"
                + "UUID: `" + player.getUuidAsString() + "`\n"
                + "World: `" + world + "`\n"
                + "Pos: `" + Math.round(player.getX()) + ", " + Math.round(player.getY()) + ", " + Math.round(player.getZ()) + "`\n"
                + "Health: `" + Math.round(player.getHealth() * 10.0) / 10.0 + "`\n"
                + "Gamemode: `" + player.interactionManager.getGameMode().asString() + "`";
            event.reply(info).setEphemeral(true).queue();
        }

        private void handleInventoryCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.allowSensitivePlayerData) {
                event.reply("Sensitive player data is disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("name") == null) {
                event.reply("Server unavailable or invalid player name.").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("name").getAsString().trim();
            var player = server.getPlayerManager().getPlayer(name);
            if (player == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            String text = summarizeInventory(player.getInventory(), "Inventory");
            event.reply(text).setEphemeral(true).queue();
        }

        private void handleEnderChestCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.allowSensitivePlayerData) {
                event.reply("Sensitive player data is disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("name") == null) {
                event.reply("Server unavailable or invalid player name.").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("name").getAsString().trim();
            var player = server.getPlayerManager().getPlayer(name);
            if (player == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            String text = summarizeInventory(player.getEnderChestInventory(), "Ender Chest");
            event.reply(text).setEphemeral(true).queue();
        }

        private void handleKickCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("player") == null) {
                event.reply("Missing player").setEphemeral(true).queue();
                return;
            }
            String player = event.getOption("player").getAsString().trim();
            String reason = event.getOption("reason") == null ? "No reason" : event.getOption("reason").getAsString().replace('\n', ' ').trim();
            if (server != null) {
                String cmd = "kick " + player + " " + reason;
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Kicked `" + player + "`").queue();
            sendAdminActionLog("Discord kick by " + event.getUser().getEffectiveName() + "\nTarget: " + player + "\nReason: " + reason);
        }

        private void handleGamemodeCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("player") == null || event.getOption("mode") == null) {
                event.reply("Missing player/mode").setEphemeral(true).queue();
                return;
            }
            String player = event.getOption("player").getAsString().trim();
            String mode = event.getOption("mode").getAsString().trim().toLowerCase();
            if (!mode.equals("survival") && !mode.equals("creative") && !mode.equals("adventure") && !mode.equals("spectator")) {
                event.reply("Invalid mode. Use survival/creative/adventure/spectator").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                String cmd = "gamemode " + mode + " " + player;
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Gamemode updated: `" + player + "` -> `" + mode + "`").queue();
            sendAdminActionLog("Discord gamemode by " + event.getUser().getEffectiveName() + "\nTarget: " + player + "\nMode: " + mode);
        }

        private void handleFreezeCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null) {
                event.reply("Server unavailable or player missing.").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            var player = server.getPlayerManager().getPlayer(name);
            if (player == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            AntiCheatManager.freezePlayer(player.getUuid());
            event.reply("Player frozen: `" + name + "`").queue();
        }

        private void handleUnfreezeCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null) {
                event.reply("Server unavailable or player missing.").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            var player = server.getPlayerManager().getPlayer(name);
            if (player == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            AntiCheatManager.unfreezePlayer(player.getUuid());
            event.reply("Player unfrozen: `" + name + "`").queue();
        }

        private void handleSetBalanceCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null || event.getOption("amount") == null) {
                event.reply("Missing player/amount").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            double amount = event.getOption("amount").getAsDouble();
            var player = server.getPlayerManager().getPlayer(name);
            if (player == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            EconomyManager.setBalance(player.getUuid(), amount);
            event.reply("Balance set: `" + name + "` -> " + EconomyManager.formatCurrency(amount)).queue();
        }

        private void handleWhitelistCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("action") == null) {
                event.reply("Missing action").setEphemeral(true).queue();
                return;
            }
            String action = event.getOption("action").getAsString().trim().toLowerCase();
            String player = event.getOption("player") == null ? "" : event.getOption("player").getAsString().trim();
            String cmd = switch (action) {
                case "on" -> "whitelist on";
                case "off" -> "whitelist off";
                case "add" -> player.isBlank() ? null : "whitelist add " + player;
                case "remove" -> player.isBlank() ? null : "whitelist remove " + player;
                default -> null;
            };
            if (cmd == null) {
                event.reply("Usage: action on/off/add/remove (+ player for add/remove)").setEphemeral(true).queue();
                return;
            }
            if (!isAllowedRemoteCommand(cmd)) {
                event.reply("Command blocked by allowedCommandPrefixes").setEphemeral(true).queue();
                return;
            }
            server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            event.reply("Whitelist action executed: `" + cmd + "`").queue();
        }

        private void handleServerOverviewCommand(SlashCommandInteractionEvent event) {
            if (server == null) {
                event.reply("Server unavailable").setEphemeral(true).queue();
                return;
            }
            String overview = "Players: `" + server.getCurrentPlayerCount() + "/" + server.getMaxPlayerCount() + "`\n"
                + "Whitelist: `" + (server.getPlayerManager().isWhitelistEnabled() ? "ON" : "OFF") + "`\n"
                + "Banned users: `" + server.getPlayerManager().getUserBanList().getNames().length + "`\n"
                + "Ops: `" + server.getPlayerManager().getOpList().getNames().length + "`\n"
                + "Top command: `" + topCommandFromLogs() + "`";
            event.reply(overview).setEphemeral(true).queue();
        }

        private void handleSaveAllCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            String cmd = "save-all";
            if (!isAllowedRemoteCommand(cmd)) {
                event.reply("Command blocked by allowedCommandPrefixes").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Executed: `" + cmd + "`").queue();
        }

        private void handleOpCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("player") == null) {
                event.reply("Missing player").setEphemeral(true).queue();
                return;
            }
            String player = event.getOption("player").getAsString().trim();
            String cmd = "op " + player;
            if (!isAllowedRemoteCommand(cmd)) {
                event.reply("Command blocked by allowedCommandPrefixes").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Executed: `" + cmd + "`").queue();
        }

        private void handleDeopCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("player") == null) {
                event.reply("Missing player").setEphemeral(true).queue();
                return;
            }
            String player = event.getOption("player").getAsString().trim();
            String cmd = "deop " + player;
            if (!isAllowedRemoteCommand(cmd)) {
                event.reply("Command blocked by allowedCommandPrefixes").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Executed: `" + cmd + "`").queue();
        }

        private void handlePardonCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (event.getOption("player") == null) {
                event.reply("Missing player").setEphemeral(true).queue();
                return;
            }
            String player = event.getOption("player").getAsString().trim();
            String cmd = "pardon " + player;
            if (!isAllowedRemoteCommand(cmd)) {
                event.reply("Command blocked by allowedCommandPrefixes").setEphemeral(true).queue();
                return;
            }
            if (server != null) {
                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
            }
            event.reply("Executed: `" + cmd + "`").queue();
        }

        private void handleEconomyTopCommand(SlashCommandInteractionEvent event) {
            if (server == null) {
                event.reply("Server unavailable").setEphemeral(true).queue();
                return;
            }
            var snap = EconomyManager.snapshotBalances(EconomyManager.Currency.COINS);
            if (snap.isEmpty()) {
                event.reply("No economy data yet.").setEphemeral(true).queue();
                return;
            }
            StringBuilder sb = new StringBuilder("Top balances (Coins):\n");
            snap.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(10)
                .forEach(e -> sb.append("• `").append(e.getKey().toString(), 0, 8).append("` -> ")
                    .append(EconomyManager.formatCurrency(e.getValue(), EconomyManager.Currency.COINS)).append('\n'));
            event.reply(sb.toString().trim()).setEphemeral(true).queue();
        }

        private void handleEconomyPlayerCommand(SlashCommandInteractionEvent event) {
            if (server == null || event.getOption("player") == null) {
                event.reply("Server unavailable or missing player.").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            var p = server.getPlayerManager().getPlayer(name);
            if (p == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            UUID uuid = p.getUuid();
            StringBuilder sb = new StringBuilder("Economy for `").append(p.getName().getString()).append("`:\n");
            for (EconomyManager.Currency c : EconomyManager.Currency.values()) {
                sb.append("• ").append(c.displayName).append(": ").append(EconomyManager.formatCurrency(EconomyManager.getBalance(uuid, c), c)).append('\n');
            }
            event.reply(sb.toString().trim()).setEphemeral(true).queue();
        }

        private void handleClaimsOverviewCommand(SlashCommandInteractionEvent event) {
            var snap = ClaimManager.getAllClaimsSnapshot();
            int count = snap.size();
            StringBuilder sb = new StringBuilder("Claims: `").append(count).append("`\nSample:\n");
            snap.entrySet().stream().limit(8).forEach(e -> {
                sb.append("• `").append(e.getKey()).append("` ").append(e.getValue().type == null ? "?" : e.getValue().type.name())
                    .append(e.getValue().isOverdue ? " (overdue)" : "")
                    .append('\n');
            });
            event.reply(sb.toString().trim()).setEphemeral(true).queue();
        }

        private void handleClansOverviewCommand(SlashCommandInteractionEvent event) {
            var clans = ClanManager.getAllClans();
            StringBuilder sb = new StringBuilder("Clans: `").append(clans.size()).append("`\nTop:\n");
            clans.values().stream()
                .sorted((a, b) -> Integer.compare(b.members.size(), a.members.size()))
                .limit(8)
                .forEach(c -> sb.append("• `").append(c.name).append("` [").append(c.tag).append("] members=").append(c.members.size()).append(" bank=").append((long) c.bankBalance).append('\n'));
            event.reply(sb.toString().trim()).setEphemeral(true).queue();
        }

        private void handleNoteCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null || event.getOption("text") == null) {
                event.reply("Missing player/text").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            String text = event.getOption("text").getAsString().replace('\n', ' ').trim();
            var p = server.getPlayerManager().getPlayer(name);
            if (p == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            ModerationManager.addNote(p.getUuid(), null, text);
            event.reply("Note added for `" + name + "`.").setEphemeral(true).queue();
        }

        private void handleWarnCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null || event.getOption("reason") == null) {
                event.reply("Missing player/reason").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            String reason = event.getOption("reason").getAsString().replace('\n', ' ').trim();
            var p = server.getPlayerManager().getPlayer(name);
            if (p == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            ModerationManager.addWarn(p.getUuid(), null, reason);
            event.reply("Warn added for `" + name + "`.").setEphemeral(true).queue();
        }

        private void handleMuteCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null) {
                event.reply("Missing player").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            var p = server.getPlayerManager().getPlayer(name);
            if (p == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            long minutes = event.getOption("minutes") == null ? 0 : event.getOption("minutes").getAsLong();
            String reason = event.getOption("reason") == null ? "" : event.getOption("reason").getAsString().replace('\n', ' ').trim();
            long durationMs = minutes <= 0 ? 0 : minutes * 60_000L;
            ModerationManager.mute(p.getUuid(), null, durationMs, reason);
            p.sendMessage(Text.literal("§cYou have been muted." + (reason.isBlank() ? "" : (" Reason: " + reason))), false);
            event.reply("Muted `" + name + "`" + (minutes > 0 ? (" for " + minutes + "m") : " (permanent)") + ".").queue();
        }

        private void handleUnmuteCommand(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableRemoteServerCommands) {
                event.reply("Remote controls are disabled in config.").setEphemeral(true).queue();
                return;
            }
            if (server == null || event.getOption("player") == null) {
                event.reply("Missing player").setEphemeral(true).queue();
                return;
            }
            String name = event.getOption("player").getAsString().trim();
            var p = server.getPlayerManager().getPlayer(name);
            if (p == null) {
                event.reply("Player `" + name + "` is offline.").setEphemeral(true).queue();
                return;
            }
            ModerationManager.unmute(p.getUuid());
            p.sendMessage(Text.literal("§aYou have been unmuted."), false);
            event.reply("Unmuted `" + name + "`.").queue();
        }
    }

    private static String summarizeInventory(net.minecraft.inventory.Inventory inv, String title) {
        StringBuilder sb = new StringBuilder(title).append(":\n");
        int printed = 0;
        for (int i = 0; i < inv.size(); i++) {
            var s = inv.getStack(i);
            if (s == null || s.isEmpty()) continue;
            sb.append("`").append(i).append("` ").append(s.getName().getString()).append(" x").append(s.getCount()).append('\n');
            printed++;
            if (printed >= 25) break;
        }
        if (printed == 0) return title + ": (empty)";
        return sb.toString().trim();
    }

    private static String topCommandFromLogs() {
        Map<String, Integer> counts = new HashMap<>();
        for (var e : LoggingManager.getRecentLogs(250)) {
            if (!"command".equalsIgnoreCase(e.channel)) continue;
            int idx = e.message.indexOf(" executed: ");
            if (idx < 0) continue;
            String cmd = e.message.substring(idx + " executed: ".length()).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.isBlank()) continue;
            String root = cmd.split("\\s+")[0].toLowerCase();
            counts.merge(root, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> "/" + e.getKey() + " (" + e.getValue() + ")")
            .orElse("n/a");
    }

    private static boolean isAllowedRemoteCommand(String command) {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.discord == null) return false;
        String[] prefixes = cfg.discord.allowedCommandPrefixes;
        if (prefixes == null || prefixes.length == 0) return true;
        String first = command.split("\\s+")[0].toLowerCase();
        return Arrays.stream(prefixes)
            .filter(x -> x != null && !x.isBlank())
            .map(x -> x.trim().toLowerCase())
            .anyMatch(first::equals);
    }

    private static String getBountyList() {
        Map<UUID, Double> bounties = BountyManager.getAllBounties();
        if (bounties.isEmpty()) {
            return "📋 No active bounties.";
        }

        StringBuilder sb = new StringBuilder("📋 Active bounties:\n");
        bounties.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(10)
            .forEach(entry -> {
                String name = getPlayerName(entry.getKey());
                sb.append("- ").append(name).append(": ").append(EconomyManager.formatCurrency(entry.getValue())).append("\n");
            });
        return sb.toString().trim();
    }

    private static String getPlayerName(UUID uuid) {
        if (server == null) return uuid.toString().substring(0, 8);
        var online = server.getPlayerManager().getPlayer(uuid);
        if (online != null) {
            return online.getName().getString();
        }
        return uuid.toString().substring(0, 8);
    }

    private static class WebhookPayload {
        @SuppressWarnings("unused")
        public Embed[] embeds;

        public WebhookPayload(Embed embed) {
            this.embeds = new Embed[]{embed};
        }
    }

    private static class Embed {
        @SuppressWarnings("unused")
        public String title;
        @SuppressWarnings("unused")
        public String description;
        @SuppressWarnings("unused")
        public int color;
        @SuppressWarnings("unused")
        public String timestamp;
        @SuppressWarnings("unused")
        public Footer footer;
        @SuppressWarnings("unused")
        public Field[] fields;

        public Embed(String title, String description, int color, List<Field> fields) {
            this.title = title;
            this.description = description;
            this.color = color;
            this.timestamp = Instant.now().toString();
            this.footer = new Footer(server != null ? ("Core • " + server.getVersion()) : "Core");
            this.fields = fields == null ? new Field[0] : fields.toArray(new Field[0]);
        }
    }

    private static class Footer {
        @SuppressWarnings("unused")
        public String text;

        public Footer(String text) {
            this.text = text;
        }
    }

    private static class Field {
        @SuppressWarnings("unused")
        public String name;
        @SuppressWarnings("unused")
        public String value;
        @SuppressWarnings("unused")
        public boolean inline;

        public Field(String name, String value, boolean inline) {
            this.name = name;
            this.value = value;
            this.inline = inline;
        }
    }

    private static void shutdown() {
        botStarted = false;
        if (jda != null) {
            try {
                jda.shutdownNow();
            } catch (Exception e) {
                LOGGER.warn("Error while shutting down JDA", e);
            } finally {
                jda = null;
            }
        }

        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EXECUTOR.shutdownNow();
        }
    }
}
