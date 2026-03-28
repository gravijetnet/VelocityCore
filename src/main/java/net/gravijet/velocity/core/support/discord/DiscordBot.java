package net.gravijet.velocity.core.support.discord;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.Category;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.TextChannelCreateSpec;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.util.Color;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.config.SupportConfig;
import net.gravijet.velocity.core.support.manager.SupportManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordBot {
    private final SupportPlugin plugin;
    private final SupportConfig config;
    private final SupportManager manager;
    private GatewayDiscordClient client;

    // Session <-> Channel mapping
    private final Map<String, TextChannel> sessionToChannel    = new ConcurrentHashMap<>();
    private final Map<String, String>      channelToSession    = new ConcurrentHashMap<>();
    private final Map<String, String>      channelToPlayer     = new ConcurrentHashMap<>();
    private final Map<String, String>      sessionToEmbedMsgId = new ConcurrentHashMap<>();
    private final Map<String, String>      discordStaffSession = new ConcurrentHashMap<>();

    // Claim tracking: channelId -> Discord user ID / display name
    private final Map<String, String> claimedByUserId = new ConcurrentHashMap<>();
    private final Map<String, String> claimedByName   = new ConcurrentHashMap<>();

    public DiscordBot(SupportPlugin plugin, SupportConfig config, SupportManager manager) {
        this.plugin  = plugin;
        this.config  = config;
        this.manager = manager;
    }

    public void start() {
        try {
            String token = config.getDiscordSettings().botToken;
            this.client = DiscordClient.create(token).login().block();
            if (client == null) {
                plugin.getLogger().error("Discord login failed.");
                return;
            }
            // Use reactive handler pattern — handlers return Mono<Void> so Discord4J
            // can properly propagate errors and backpressure instead of blocking the
            // event-dispatch thread.
            client.on(ReadyEvent.class,
                    e -> Mono.fromRunnable(() -> onReady(e)).subscribeOn(Schedulers.boundedElastic())).subscribe();
            client.on(ButtonInteractionEvent.class, this::onButtonInteraction).subscribe();
            client.on(MessageCreateEvent.class,     this::onMessageCreate).subscribe();
            client.on(ChatInputInteractionEvent.class, this::onSlashCommand).subscribe();
            plugin.getLogger().info("Discord bot started.");
        } catch (Exception e) {
            plugin.getLogger().error("Failed to start Discord bot.", e);
        }
    }

    public void stop() {
        if (client != null) client.logout().block();
    }

    // -------------------------------------------------------------------------
    // Ready (runs on boundedElastic — blocking OK)
    // -------------------------------------------------------------------------

    private void onReady(ReadyEvent event) {
        plugin.getLogger().info("Discord bot connected as {}.", event.getSelf().getUsername());
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();

        Guild guild = client.getGuildById(Snowflake.of(cfg.allowedGuildId)).block();
        if (guild == null) {
            plugin.getLogger().error("Bot is not in the configured guild. Shutting down.");
            client.logout().block();
            return;
        }

        try {
            Long appId = client.getRestClient().getApplicationId().block();
            if (appId != null) {
                client.getRestClient().getApplicationService()
                        .createGuildApplicationCommand(appId, Long.parseLong(cfg.allowedGuildId),
                                ApplicationCommandRequest.builder()
                                        .name("stats")
                                        .description("Show support rating statistics")
                                        .addOption(ApplicationCommandOptionData.builder()
                                                .name("visible")
                                                .description("Show the response publicly (default: only visible to you)")
                                                .type(ApplicationCommandOption.Type.BOOLEAN.getValue())
                                                .required(false)
                                                .build())
                                        .build())
                        .block();
                plugin.getLogger().info("Registered /stats command.");
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Could not register /stats command: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Button interactions — fully reactive; blocking work on boundedElastic
    // -------------------------------------------------------------------------

    private Mono<Void> onButtonInteraction(ButtonInteractionEvent event) {
        Member member = event.getInteraction().getMember().orElse(null);
        if (member == null) {
            return event.deferReply().withEphemeral(true)
                    .then(event.createFollowup("This button can only be used by server members.")
                            .withEphemeral(true).then());
        }

        // deferReply must complete before any blocking work
        return event.deferReply().withEphemeral(true)
                .then(hasStaffRoleMono(member))
                .flatMap(hasRole -> {
                    if (!hasRole) {
                        return event.createFollowup("You do not have permission to use support buttons.")
                                .withEphemeral(true).then();
                    }
                    String id = event.getCustomId();
                    if (id.startsWith("claim_")) return handleClaimButton(event, member);
                    if (id.startsWith("close_")) return handleCloseButton(event, member);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    plugin.getLogger().error("Error handling button interaction.", e);
                    return event.createFollowup("An internal error occurred.").withEphemeral(true).then();
                });
    }

    // Runs on boundedElastic — .block() calls inside are safe
    private Mono<Void> handleClaimButton(ButtonInteractionEvent event, Member member) {
        return Mono.fromCallable(() -> {
            String channelId = event.getCustomId().substring(6);
            if (claimedByUserId.containsKey(channelId)) {
                String claimer = claimedByName.getOrDefault(channelId, "someone");
                return config.getDiscordSettings().alreadyClaimedMessage.replace("{staff}", claimer);
            }

            String sessionId = channelToSession.get(channelId);
            if (sessionId == null) return "No active support session found for this channel.";

            String playerName = channelToPlayer.get(channelId);
            if (playerName == null) {
                TextChannel ch = (TextChannel) client.getChannelById(Snowflake.of(channelId)).block();
                playerName = ch != null ? extractFromChannelName(ch.getName(), 2) : "unknown";
            }

            String discordId    = member.getId().asString();
            String linkedMcName = manager.getLinkedMinecraftName(discordId);
            String claimName    = linkedMcName != null ? linkedMcName : member.getDisplayName();

            boolean ok = manager.claimSupportFromDiscord(discordId, claimName, playerName, false);
            if (ok) {
                claimedByUserId.put(channelId, discordId);
                claimedByName.put(channelId, claimName);
                discordStaffSession.put(discordId, sessionId);
                updateChannelToClaimed(sessionId, claimName, discordId);
                return "You have claimed this support request.";
            }
            return "Could not claim the support request.";
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(msg -> event.createFollowup(msg).withEphemeral(true).then());
    }

    // Runs on boundedElastic — .block() calls inside are safe
    private Mono<Void> handleCloseButton(ButtonInteractionEvent event, Member member) {
        return Mono.fromCallable(() -> {
            String channelId     = event.getCustomId().substring(6);
            String claimerUserId = claimedByUserId.get(channelId);

            if (claimerUserId == null || !claimerUserId.equals(member.getId().asString())) {
                if (!hasManagementRole(member)) {
                    return config.getDiscordSettings().onlyClaimerCanClose;
                }
            }

            boolean ok = manager.closeSupportSessionFromDiscord(member.getId().asString());
            if (!ok && claimerUserId != null) {
                ok = manager.closeSupportSessionFromDiscord(claimerUserId);
            }
            return ok ? "Support session closed." : "Could not close the session.";
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(msg -> event.createFollowup(msg).withEphemeral(true).then());
    }

    // -------------------------------------------------------------------------
    // Message create (Discord -> Minecraft relay) — fully reactive, no blocking
    // -------------------------------------------------------------------------

    private Mono<Void> onMessageCreate(MessageCreateEvent event) {
        Message msg = event.getMessage();
        if (msg.getAuthor().map(discord4j.core.object.entity.User::isBot).orElse(true)) {
            return Mono.empty();
        }

        return msg.getChannel()
                .ofType(TextChannel.class)
                .flatMap(channel -> {
                    String channelId = channel.getId().asString();
                    String sessionId = channelToSession.get(channelId);
                    if (sessionId == null) return Mono.empty();

                    if (!claimedByUserId.containsKey(channelId)) {
                        return msg.delete()
                                .then(channel.createMessage(config.getDiscordSettings().pleaseClaimMessage))
                                .then();
                    }

                    return msg.getAuthorAsMember()
                            .doOnNext(member ->
                                    manager.handleSupportChatFromDiscord(member.getId().asString(), msg.getContent()))
                            .then();
                })
                .onErrorResume(e -> {
                    plugin.getLogger().error("Error processing Discord message.", e);
                    return Mono.empty();
                });
    }

    // -------------------------------------------------------------------------
    // Slash commands — reactive; chart generation on boundedElastic
    // -------------------------------------------------------------------------

    private Mono<Void> onSlashCommand(ChatInputInteractionEvent event) {
        if (!"stats".equals(event.getCommandName())) return Mono.empty();

        Member member = event.getInteraction().getMember().orElse(null);
        if (member == null) {
            return event.reply("You do not have permission to use this command.").withEphemeral(true).then();
        }

        int total = manager.getTotalRatedCount();
        if (total == 0) {
            return event.reply("No ratings have been submitted yet.").withEphemeral(true).then();
        }

        boolean visible = event.getOption("visible")
                .flatMap(opt -> opt.getValue())
                .map(v -> v.asBoolean())
                .orElse(false);

        return hasStaffRoleMono(member)
                .flatMap(hasRole -> {
                    if (!hasRole) {
                        return event.reply("You do not have permission to use this command.")
                                .withEphemeral(true).then();
                    }
                    return event.deferReply().withEphemeral(!visible)
                            .then(Mono.fromCallable(() -> {
                                Map<String, SupportManager.StaffStats> staffStats = manager.buildStaffStats();
                                double avg = manager.getGlobalAverageRating();
                                return StatsChartGenerator.generate(staffStats, avg, total, plugin.getLogger());
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .flatMap(chart -> {
                                if (chart == null) {
                                    return event.editReply().withContent("Failed to generate chart.").then();
                                }
                                try {
                                    return event.editReply().withContentOrNull(null)
                                            .then(event.createFollowup()
                                                    .withEphemeral(!visible)
                                                    .withFiles(discord4j.core.spec.MessageCreateFields.File.of(
                                                            "stats.png", Files.newInputStream(chart.toPath())))
                                                    .then())
                                            .doFinally(s -> chart.delete());
                                } catch (Exception e) {
                                    plugin.getLogger().error("Error sending stats chart.", e);
                                    return event.editReply().withContent("Failed to load statistics.").then();
                                }
                            })
                            .onErrorResume(e -> {
                                plugin.getLogger().error("Error handling /stats command.", e);
                                return event.editReply().withContent("Failed to load statistics.").then();
                            });
                });
    }

    // -------------------------------------------------------------------------
    // Public API for SupportManager (called from Velocity threads — blocking OK)
    // -------------------------------------------------------------------------

    public void createSupportChannel(String playerName, String serverName, String language, String sessionId) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        try {
            Guild guild = client.getGuildById(Snowflake.of(cfg.allowedGuildId)).block();
            if (guild == null) return;
            Category cat = (Category) client.getChannelById(Snowflake.of(cfg.supportCategoryId)).block();
            if (cat == null) return;

            String name = buildChannelName("o", language, playerName, serverName, cfg.channelNameFormat);
            TextChannel channel = guild.createTextChannel(TextChannelCreateSpec.builder()
                    .name(name).parentId(cat.getId()).build()).block();
            if (channel == null) return;

            String chId = channel.getId().asString();
            sessionToChannel.put(sessionId, channel);
            channelToSession.put(chId, sessionId);
            channelToPlayer.put(chId, playerName);

            EmbedCreateSpec embed = EmbedCreateSpec.builder()
                    .color(parseColor(cfg.openColor))
                    .title("Support Request")
                    .addField("Player",   playerName,             true)
                    .addField("Server",   serverName,             true)
                    .addField("Language", language.toUpperCase(), true)
                    .addField("Status",   "Open",                 true)
                    .timestamp(Instant.now())
                    .build();

            Message embedMsg = channel.createMessage(MessageCreateSpec.builder()
                    .addEmbed(embed)
                    .addComponent(ActionRow.of(
                            Button.success("claim_" + chId, "Claim"),
                            Button.danger("close_" + chId, "Close")))
                    .build()).block();

            if (embedMsg != null) sessionToEmbedMsgId.put(sessionId, embedMsg.getId().asString());
            plugin.getLogger().info("Created support channel: {}", name);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to create support channel.", e);
        }
    }

    public void updateChannelToClaimed(String sessionId, String staffName, String staffDiscordId) {
        TextChannel channel = sessionToChannel.get(sessionId);
        if (channel == null) return;
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        String chId = channel.getId().asString();

        String embedId = sessionToEmbedMsgId.get(sessionId);
        if (embedId != null) {
            Message msg = channel.getMessageById(Snowflake.of(embedId)).block();
            if (msg != null) {
                EmbedCreateSpec newEmbed = EmbedCreateSpec.builder()
                        .color(parseColor(cfg.claimedColor))
                        .title("Support Request")
                        .addField("Player", channelToPlayer.getOrDefault(chId, "?"), true)
                        .addField("Staff",  staffName,                               true)
                        .addField("Status", "Claimed",                               true)
                        .timestamp(Instant.now())
                        .build();
                msg.edit()
                        .withEmbeds(newEmbed)
                        .withComponents(ActionRow.of(
                                Button.success("claim_" + chId, "Claimed").disabled(),
                                Button.danger("close_" + chId, "Close")))
                        .block();
            }
        }

        channel.edit(spec -> spec.setName(channel.getName().replaceFirst("^o-", "c-"))).subscribe();
        claimedByUserId.put(chId, staffDiscordId);
        claimedByName.put(chId, staffName);
        discordStaffSession.put(staffDiscordId, sessionId);
        sendToChannel(sessionId, cfg.claimMessage.replace("{staff}", staffName));
    }

    public void sendMessageToDiscord(String sessionId, String message, String sender, boolean isStaff) {
        TextChannel channel = sessionToChannel.get(sessionId);
        if (channel == null) return;
        String formatted = isStaff ? "**[Team] " + sender + "**: " + message : "**" + sender + "**: " + message;
        channel.createMessage(formatted).subscribe();
    }

    public void sendToChannel(String sessionId, String message) {
        TextChannel channel = sessionToChannel.get(sessionId);
        if (channel != null) channel.createMessage(message).subscribe();
    }

    public void sendTransferMessageToChannel(String sessionId, String from, String to) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        sendToChannel(sessionId, cfg.transferMessage.replace("{fromStaff}", from).replace("{toStaff}", to));
    }

    public void sendStatusMessageToChannel(String sessionId, String status) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        sendToChannel(sessionId, cfg.statusMessage.replace("{status}", status));
    }

    public void closeSupportChannel(String sessionId, String language, String playerName, Instant createdAt) {
        TextChannel channel = sessionToChannel.get(sessionId);
        if (channel == null) return;
        String chId = channel.getId().asString();

        try {
            sendTranscript(channel, sessionId, language, playerName, createdAt);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to send transcript.", e);
        }

        channel.delete().subscribe();

        sessionToChannel.remove(sessionId);
        channelToSession.remove(chId);
        channelToPlayer.remove(chId);
        sessionToEmbedMsgId.remove(sessionId);
        claimedByUserId.remove(chId);
        claimedByName.remove(chId);
        discordStaffSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
    }

    public void sendRatingEmbedToChannel(String sessionId, int rating, String playerName, String staffName) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        try {
            TextChannel channel = (TextChannel) client.getChannelById(Snowflake.of(cfg.transcriptChannelId)).block();
            if (channel == null) return;

            EmbedCreateSpec embed = EmbedCreateSpec.builder()
                    .color(ratingColor(rating))
                    .title("New Rating")
                    .addField("Player",       playerName,       true)
                    .addField("Staff Member", staffName,        true)
                    .addField("Rating",       rating + " / 5",  true)
                    .timestamp(Instant.now())
                    .build();

            channel.createMessage(MessageCreateSpec.builder().addEmbed(embed).build()).subscribe();
        } catch (Exception e) {
            plugin.getLogger().error("Failed to send rating embed.", e);
        }
    }

    public String getSessionIdByDiscordStaff(String discordId) {
        return discordStaffSession.get(discordId);
    }

    public void updateDiscordStaffSession(String discordId, String sessionId) {
        discordStaffSession.put(discordId, sessionId);
    }

    public void removeDiscordStaffSession(String discordId) {
        discordStaffSession.remove(discordId);
    }

    // -------------------------------------------------------------------------
    // Transcript
    // -------------------------------------------------------------------------

    private void sendTranscript(TextChannel channel, String sessionId, String language, String playerName, Instant createdAt) throws Exception {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        TextChannel transcriptCh = (TextChannel) client.getChannelById(Snowflake.of(cfg.transcriptChannelId)).block();
        if (transcriptCh == null) return;

        File file = buildTranscriptFile(channel, sessionId, language, playerName, createdAt);
        if (file == null) return;

        String date = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(
                LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()));

        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .color(Color.of(0x5865F2))
                .title(cfg.transcriptTitle)
                .addField(cfg.transcriptPlayerField,   playerName, true)
                .addField(cfg.transcriptLanguageField, language,   true)
                .addField(cfg.transcriptDateField,     date,       true)
                .timestamp(Instant.now())
                .build();

        transcriptCh.createMessage(MessageCreateSpec.builder()
                .addEmbed(embed)
                .addFile(file.getName(), Files.newInputStream(file.toPath()))
                .build()).subscribe();
        file.delete();
    }

    private File buildTranscriptFile(TextChannel channel, String sessionId, String language, String playerName, Instant createdAt) {
        try {
            File logDir = new File(plugin.getDataDirectory().toFile(), "logs");
            logDir.mkdirs();
            String fname = String.format("support_%s_%s.txt", playerName,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(
                            LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault())));
            File f = new File(logDir, fname);

            // Limit to 500 messages to prevent OOM on long-running channels
            List<Message> messages = channel.getMessagesBefore(Snowflake.of(Instant.now()))
                    .take(500)
                    .collectList()
                    .block();
            if (messages == null) return null;
            Collections.reverse(messages);

            try (FileWriter w = new FileWriter(f)) {
                w.write("Support Transcript\n");
                w.write("Player:   " + playerName + "\n");
                w.write("Language: " + language   + "\n");
                w.write("Date:     " + DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").format(
                        LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault())) + "\n");
                w.write("ID:       " + sessionId  + "\n\n");
                DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm:ss");
                for (Message msg : messages) {
                    String author  = msg.getAuthor().map(u -> u.getUsername()).orElse("?");
                    String content = msg.getContent();
                    String ts      = tf.format(msg.getTimestamp().atZone(ZoneId.systemDefault()));
                    if (!content.isBlank()) w.write("[" + ts + "] " + author + ": " + content + "\n");
                }
            }
            return f;
        } catch (Exception e) {
            plugin.getLogger().error("Failed to create transcript file.", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Role helpers
    // -------------------------------------------------------------------------

    /** Non-blocking reactive role check — use in reactive chains. */
    private Mono<Boolean> hasStaffRoleMono(Member member) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        return member.getRoles()
                .any(r -> r.getId().asString().equals(cfg.staffRoleId)
                        || r.getId().asString().equals(cfg.managementRoleId));
    }

    /** Blocking role check (uses .any() to short-circuit) — safe on boundedElastic threads. */
    private boolean hasStaffRole(Member member) {
        Boolean result = hasStaffRoleMono(member).block();
        return Boolean.TRUE.equals(result);
    }

    /** Blocking management-role check — safe on boundedElastic threads. */
    private boolean hasManagementRole(Member member) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        Boolean result = member.getRoles()
                .any(r -> r.getId().asString().equals(cfg.managementRoleId))
                .block();
        return Boolean.TRUE.equals(result);
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private Color parseColor(String s) {
        return switch (s == null ? "" : s.toUpperCase()) {
            case "GREEN"      -> Color.GREEN;
            case "RED"        -> Color.RED;
            case "BLUE"       -> Color.BLUE;
            case "YELLOW"     -> Color.YELLOW;
            case "ORANGE"     -> Color.ORANGE;
            case "CYAN"       -> Color.CYAN;
            case "GRAY"       -> Color.GRAY;
            case "DARK_GRAY"  -> Color.DARK_GRAY;
            case "LIGHT_GRAY" -> Color.LIGHT_GRAY;
            case "BLACK"      -> Color.BLACK;
            case "WHITE"      -> Color.WHITE;
            case "PURPLE"     -> Color.of(0x9B59B6);
            default           -> Color.of(0x5865F2);
        };
    }

    private Color ratingColor(int r) {
        return switch (r) {
            case 5  -> Color.GREEN;
            case 4  -> Color.of(0x57F287);
            case 3  -> Color.YELLOW;
            case 2  -> Color.ORANGE;
            default -> Color.RED;
        };
    }

    private String buildChannelName(String status, String lang, String player, String server, String fmt) {
        String safeName   = player.toLowerCase().replaceAll("[^a-z0-9_]", "");
        String safeServer = server.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        String name = fmt.replace("{status}",   status)
                .replace("{language}", lang.toLowerCase())
                .replace("{player}",   safeName.substring(0, Math.min(safeName.length(), 10)))
                .replace("{server}",   safeServer.substring(0, Math.min(safeServer.length(), 10)));
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private String extractFromChannelName(String channelName, int index) {
        String[] parts = channelName.split("-");
        return parts.length > index ? parts[index] : "unknown";
    }
}
