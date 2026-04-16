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
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.gravijet.velocity.core.util.ConfigManager;
import org.spongepowered.configurate.ConfigurationNode;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordBot {
    private final SupportPlugin plugin;
    private final ConfigManager configManager;
    private final SupportManager manager;
    private GatewayDiscordClient client;
    private String botToken;

    private final Map<String, TextChannel> sessionToChannel = new ConcurrentHashMap<>();
    private final Map<String, String> channelToSession = new ConcurrentHashMap<>();
    private final Map<String, String> channelToPlayer = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToEmbedMsgId = new ConcurrentHashMap<>();
    private final Map<String, String> discordStaffSession = new ConcurrentHashMap<>();
    private final Map<String, String> claimedByUserId = new ConcurrentHashMap<>();
    private final Map<String, String> claimedByName = new ConcurrentHashMap<>();

    public DiscordBot(SupportPlugin plugin, ConfigManager configManager, SupportManager manager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.manager = manager;
    }

    public void start() {
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        if (!discordNode.node("enabled").getBoolean(false)) {
            return;
        }
        try {
            String token = discordNode.node("bot-token").getString();
            if (token == null || token.equals("YOUR_DISCORD_BOT_TOKEN_HERE")) {
                plugin.getLogger().error("Discord bot token is not set in config.yml. Bot will not start.");
                return;
            }
            this.botToken = token;
            this.client = DiscordClient.create(token).login().block();
            if (client == null) {
                plugin.getLogger().error("Discord login failed.");
                return;
            }
            client.on(ReadyEvent.class, e -> Mono.fromRunnable(() -> onReady(e)).subscribeOn(Schedulers.boundedElastic())).subscribe();
            client.on(ButtonInteractionEvent.class, this::onButtonInteraction).subscribe();
            client.on(MessageCreateEvent.class, this::onMessageCreate).subscribe();
            client.on(ChatInputInteractionEvent.class, this::onSlashCommand).subscribe();
            plugin.getLogger().info("Discord bot started.");
        } catch (Exception e) {
            plugin.getLogger().error("Failed to start Discord bot.", e);
        }
    }

    public void stop() {
        if (client != null) client.logout().block();
    }

    private void onReady(ReadyEvent event) {
        plugin.getLogger().info("Discord bot connected as {}.", event.getSelf().getUsername());
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        String guildId = discordNode.node("guild-id").getString();
        if (guildId == null) {
            plugin.getLogger().error("Discord guild-id is not set in config.yml.");
            return;
        }

        Guild guild = client.getGuildById(Snowflake.of(guildId)).block();
        if (guild == null) {
            plugin.getLogger().error("Bot is not in the configured guild. Shutting down.");
            client.logout().block();
            return;
        }

        try {
            Long appId = client.getRestClient().getApplicationId().block();
            if (appId != null) {
                client.getRestClient().getApplicationService()
                        .createGuildApplicationCommand(appId, Long.parseLong(guildId),
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

    private Mono<Void> onButtonInteraction(ButtonInteractionEvent event) {
        Member member = event.getInteraction().getMember().orElse(null);
        if (member == null) {
            return event.deferReply().withEphemeral(true)
                    .then(event.createFollowup("This button can only be used by server members.")
                            .withEphemeral(true).then());
        }

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

    private Mono<Void> handleClaimButton(ButtonInteractionEvent event, Member member) {
        return Mono.fromCallable(() -> {
            String channelId = event.getCustomId().substring(6);
            if (claimedByUserId.containsKey(channelId)) {
                String claimer = claimedByName.getOrDefault(channelId, "someone");
                return configManager.getMessages().node("support", "discord", "already-claimed").getString().replace("{staff}", claimer);
            }

            String sessionId = channelToSession.get(channelId);
            if (sessionId == null) return "No active support session found for this channel.";

            String playerName = channelToPlayer.get(channelId);
            if (playerName == null) {
                TextChannel ch = (TextChannel) client.getChannelById(Snowflake.of(channelId)).block();
                playerName = ch != null ? extractFromChannelName(ch.getName(), 2) : "unknown";
            }

            String discordId = member.getId().asString();
            String claimName = member.getDisplayName();

            boolean ok = manager.claimSupportFromDiscord(discordId, claimName, playerName, false);
            if (ok) {
                claimedByUserId.put(channelId, discordId);
                claimedByName.put(channelId, claimName);
                discordStaffSession.put(discordId, sessionId);
                updateChannelToClaimed(sessionId, claimName, discordId);
                return configManager.getMessages().node("support", "discord", "claim-message").getString().replace("{staff}", claimName);
            }
            return "Could not claim the support request.";
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(msg -> event.createFollowup(msg).withEphemeral(true).then());
    }

    private Mono<Void> handleCloseButton(ButtonInteractionEvent event, Member member) {
        return Mono.fromCallable(() -> {
            String channelId = event.getCustomId().substring(6);
            String claimerUserId = claimedByUserId.get(channelId);

            if (claimerUserId == null || !claimerUserId.equals(member.getId().asString())) {
                if (!hasManagementRole(member)) {
                    return configManager.getMessages().node("support", "discord", "only-claimer-can-close").getString();
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

    private Mono<Void> onMessageCreate(MessageCreateEvent event) {
        Message msg = event.getMessage();
        if (msg.getAuthor().map(u -> u.isBot()).orElse(true)) {
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
                                .then(channel.createMessage(configManager.getMessages().node("support", "discord", "please-claim").getString()))
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

    public void createSupportChannel(String playerName, String serverName, String language, String sessionId) {
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        try {
            String guildId = discordNode.node("guild-id").getString();
            String supportCategoryId = discordNode.node("support-category-id").getString();
            String channelNameFormat = discordNode.node("channel-name-format").getString("{status}-{language}-{player}-{server}");
            String openColor = discordNode.node("open-color").getString("BLUE");

            Guild guild = client.getGuildById(Snowflake.of(guildId)).block();
            if (guild == null) return;
            Category cat = (Category) client.getChannelById(Snowflake.of(supportCategoryId)).block();
            if (cat == null) return;

            String name = buildChannelName("o", language, playerName, serverName, channelNameFormat);
            TextChannel channel = guild.createTextChannel(TextChannelCreateSpec.builder()
                    .name(name).parentId(cat.getId()).build()).block();
            if (channel == null) return;

            String chId = channel.getId().asString();
            sessionToChannel.put(sessionId, channel);
            channelToSession.put(chId, sessionId);
            channelToPlayer.put(chId, playerName);

            EmbedCreateSpec embed = EmbedCreateSpec.builder()
                    .color(parseColor(openColor))
                    .title(configManager.getMessages().node("support", "discord", "transcript-title").getString("Support Request"))
                    .addField("Player", playerName, true)
                    .addField("Server", serverName, true)
                    .addField("Language", language.toUpperCase(), true)
                    .addField("Status", "Open", true)
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
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        String claimedColor = discordNode.node("claimed-color").getString("YELLOW");
        String chId = channel.getId().asString();

        String embedId = sessionToEmbedMsgId.get(sessionId);
        if (embedId != null) {
            Message msg = channel.getMessageById(Snowflake.of(embedId)).block();
            if (msg != null) {
                EmbedCreateSpec newEmbed = EmbedCreateSpec.builder()
                        .color(parseColor(claimedColor))
                        .title(configManager.getMessages().node("support", "discord", "transcript-title").getString("Support Request"))
                        .addField("Player", channelToPlayer.getOrDefault(chId, "?"), true)
                        .addField("Staff", staffName, true)
                        .addField("Status", "Claimed", true)
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
        sendToChannel(sessionId, configManager.getMessages().node("support", "discord", "claim-message").getString().replace("{staff}", staffName));
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
        sendToChannel(sessionId, configManager.getMessages().node("support", "discord", "transfer-message").getString().replace("{fromStaff}", from).replace("{toStaff}", to));
    }

    public void sendStatusMessageToChannel(String sessionId, String status) {
        sendToChannel(sessionId, configManager.getMessages().node("support", "discord", "status-message").getString().replace("{status}", status));
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
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        try {
            String transcriptChannelId = discordNode.node("transcript-channel-id").getString();
            TextChannel channel = (TextChannel) client.getChannelById(Snowflake.of(transcriptChannelId)).block();
            if (channel == null) return;

            EmbedCreateSpec embed = EmbedCreateSpec.builder()
                    .color(ratingColor(rating))
                    .title(configManager.getMessages().node("support", "discord", "rating-title").getString("New Rating")) // Assuming a new message key
                    .addField("Player", playerName, true)
                    .addField("Staff Member", staffName, true)
                    .addField("Rating", rating + " / 5", true)
                    .timestamp(Instant.now())
                    .build();

            channel.createMessage(MessageCreateSpec.builder().addEmbed(embed).build()).subscribe();
        } catch (Exception e) {
            plugin.getLogger().error("Failed to send rating embed.", e);
        }
    }

    public void sendBugReport(String playerName, String serverName, String title, String message) {
        if (client == null) {
            plugin.getLogger().warn("Discord bot not connected, cannot send bug report.");
            return;
        }

        ConfigurationNode discordNode = configManager.getConfig().node("support");
        String bugChannelId = discordNode.node("bug-channel-id").getString("000000000000000000");
        String tagId = discordNode.node("bug-tag-id").getString("");

        try {
            String timeStr = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                    .format(LocalDateTime.now(ZoneId.systemDefault()));

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // Build embed as JSON
            com.fasterxml.jackson.databind.node.ObjectNode embedNode = mapper.createObjectNode();
            embedNode.put("color", 0xFFA500);
            embedNode.put("title", "Bug Report: " + title);
            com.fasterxml.jackson.databind.node.ArrayNode fieldsArray = embedNode.putArray("fields");
            fieldsArray.add(mapper.createObjectNode().put("name", "Reported by").put("value", playerName).put("inline", true));
            fieldsArray.add(mapper.createObjectNode().put("name", "Server").put("value", serverName).put("inline", true));
            fieldsArray.add(mapper.createObjectNode().put("name", "Time").put("value", timeStr).put("inline", true));
            fieldsArray.add(mapper.createObjectNode().put("name", "Description").put("value", message).put("inline", false));

            // Build message payload
            com.fasterxml.jackson.databind.node.ObjectNode msgNode = mapper.createObjectNode();
            if (tagId != null && !tagId.isEmpty()) {
                msgNode.put("content", "<@&" + tagId + ">");
            }
            msgNode.putArray("embeds").add(embedNode);

            // Build thread creation request body
            String threadName = "Bug: " + title.substring(0, Math.min(title.length(), 90));
            com.fasterxml.jackson.databind.node.ObjectNode bodyNode = mapper.createObjectNode();
            bodyNode.put("name", threadName);
            bodyNode.set("message", msgNode);
            if (tagId != null && !tagId.isEmpty()) {
                bodyNode.putArray("applied_tags").add(tagId);
            }

            String requestBody = mapper.writeValueAsString(bodyNode);

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://discord.com/api/v10/channels/" + bugChannelId + "/threads"))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(
                    httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                plugin.getLogger().error("Failed to send bug report. Status: {}, Body: {}",
                        response.statusCode(), response.body());
                return;
            }

            plugin.getLogger().info("Bug report sent from {} on {}: {}", playerName, serverName, title);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to send bug report.", e);
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

    private void sendTranscript(TextChannel channel, String sessionId, String language, String playerName, Instant createdAt) throws Exception {
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        String transcriptChannelId = discordNode.node("transcript-channel-id").getString();
        TextChannel transcriptCh = (TextChannel) client.getChannelById(Snowflake.of(transcriptChannelId)).block();
        if (transcriptCh == null) return;

        File file = buildTranscriptFile(channel, sessionId, language, playerName, createdAt);
        if (file == null) return;

        String date = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(
                LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()));

        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .color(parseColor(discordNode.node("transcript-color").getString("PURPLE")))
                .title(configManager.getMessages().node("support", "discord", "transcript-title").getString("Support Transcript"))
                .addField(configManager.getMessages().node("support", "discord", "transcript-player-field").getString("Player"), playerName, true)
                .addField(configManager.getMessages().node("support", "discord", "transcript-language-field").getString("Language"), language, true)
                .addField(configManager.getMessages().node("support", "discord", "transcript-date-field").getString("Date"), date, true)
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

            List<Message> messages = channel.getMessagesBefore(Snowflake.of(Instant.now()))
                    .take(500)
                    .collectList()
                    .block();
            if (messages == null) return null;
            Collections.reverse(messages);

            try (FileWriter w = new FileWriter(f)) {
                w.write("Support Transcript\n");
                w.write("Player:   " + playerName + "\n");
                w.write("Language: " + language + "\n");
                w.write("Date:     " + DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").format(
                        LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault())) + "\n");
                w.write("ID:       " + sessionId + "\n\n");
                DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm:ss");
                for (Message msg : messages) {
                    String author = msg.getAuthor().map(u -> u.getUsername()).orElse("?");
                    String content = msg.getContent();
                    String ts = tf.format(msg.getTimestamp().atZone(ZoneId.systemDefault()));
                    if (!content.isBlank()) w.write("[" + ts + "] " + author + ": " + content + "\n");
                }
            }
            return f;
        } catch (Exception e) {
            plugin.getLogger().error("Failed to create transcript file.", e);
            return null;
        }
    }

    private Mono<Boolean> hasStaffRoleMono(Member member) {
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        String staffRoleId = discordNode.node("staff-role-id").getString();
        String managementRoleId = discordNode.node("management-role-id").getString();
        if (staffRoleId == null && managementRoleId == null) return Mono.just(false);

        return member.getRoles()
                .any(r -> (staffRoleId != null && r.getId().asString().equals(staffRoleId)) ||
                        (managementRoleId != null && r.getId().asString().equals(managementRoleId)));
    }

    private boolean hasManagementRole(Member member) {
        ConfigurationNode discordNode = configManager.getConfig().node("support", "discord");
        String managementRoleId = discordNode.node("management-role-id").getString();
        if (managementRoleId == null) return false;

        Boolean result = member.getRoles()
                .any(r -> r.getId().asString().equals(managementRoleId))
                .block();
        return Boolean.TRUE.equals(result);
    }

    private Color parseColor(String s) {
        return switch (s == null ? "" : s.toUpperCase()) {
            case "GREEN" -> Color.GREEN;
            case "RED" -> Color.RED;
            case "BLUE" -> Color.BLUE;
            case "YELLOW" -> Color.YELLOW;
            case "ORANGE" -> Color.ORANGE;
            case "CYAN" -> Color.CYAN;
            case "GRAY" -> Color.GRAY;
            case "DARK_GRAY" -> Color.DARK_GRAY;
            case "LIGHT_GRAY" -> Color.LIGHT_GRAY;
            case "BLACK" -> Color.BLACK;
            case "WHITE" -> Color.WHITE;
            case "PURPLE" -> Color.of(0x9B59B6);
            default -> Color.of(0x5865F2);
        };
    }

    private Color ratingColor(int r) {
        return switch (r) {
            case 5 -> Color.GREEN;
            case 4 -> Color.of(0x57F287);
            case 3 -> Color.YELLOW;
            case 2 -> Color.ORANGE;
            default -> Color.RED;
        };
    }

    private String buildChannelName(String status, String lang, String player, String server, String fmt) {
        String safeName = player.toLowerCase().replaceAll("[^a-z0-9_]", "");
        String safeServer = server.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        String name = fmt.replace("{status}", status)
                .replace("{language}", lang.toLowerCase())
                .replace("{player}", safeName.substring(0, Math.min(safeName.length(), 10)))
                .replace("{server}", safeServer.substring(0, Math.min(safeServer.length(), 10)));
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private String extractFromChannelName(String channelName, int index) {
        String[] parts = channelName.split("-");
        return parts.length > index ? parts[index] : "unknown";
    }
}
