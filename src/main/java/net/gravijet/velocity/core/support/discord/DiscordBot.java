package net.gravijet.velocity.core.support.discord;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
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
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.util.Color;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.config.SupportConfig;
import net.gravijet.velocity.core.support.manager.SupportManager;

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
            client.on(ReadyEvent.class).subscribe(this::onReady);
            client.on(ButtonInteractionEvent.class).subscribe(this::onButtonInteraction);
            client.on(MessageCreateEvent.class).subscribe(this::onMessageCreate);
            client.on(ChatInputInteractionEvent.class).subscribe(this::onSlashCommand);
            plugin.getLogger().info("Discord bot started.");
        } catch (Exception e) {
            plugin.getLogger().error("Failed to start Discord bot.", e);
        }
    }

    public void stop() {
        if (client != null) client.logout().block();
    }

    // -------------------------------------------------------------------------
    // Ready
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
    // Button interactions
    // -------------------------------------------------------------------------

    private void onButtonInteraction(ButtonInteractionEvent event) {
        event.deferReply().withEphemeral(true).subscribe();
        try {
            Member member = event.getInteraction().getMember().orElse(null);
            if (member == null) {
                event.createFollowup("This button can only be used by server members.").withEphemeral(true).subscribe();
                return;
            }
            if (!hasStaffRole(member)) {
                event.createFollowup("You do not have permission to use support buttons.").withEphemeral(true).subscribe();
                return;
            }
            String id = event.getCustomId();
            if      (id.startsWith("claim_")) handleClaimButton(event, member);
            else if (id.startsWith("close_")) handleCloseButton(event, member);
        } catch (Exception e) {
            plugin.getLogger().error("Error handling button interaction.", e);
            event.createFollowup("An internal error occurred.").withEphemeral(true).subscribe();
        }
    }

    private void handleClaimButton(ButtonInteractionEvent event, Member member) {
        String channelId = event.getCustomId().substring(6);
        if (claimedByUserId.containsKey(channelId)) {
            String claimer = claimedByName.getOrDefault(channelId, "someone");
            event.createFollowup(config.getDiscordSettings().alreadyClaimedMessage.replace("{staff}", claimer))
                    .withEphemeral(true).subscribe();
            return;
        }

        String sessionId = channelToSession.get(channelId);
        if (sessionId == null) {
            event.createFollowup("No active support session found for this channel.").withEphemeral(true).subscribe();
            return;
        }

        String playerName = channelToPlayer.get(channelId);
        if (playerName == null) {
            TextChannel ch = (TextChannel) client.getChannelById(Snowflake.of(channelId)).block();
            playerName = ch != null ? extractFromChannelName(ch.getName(), 2) : "unknown";
        }

        String discordId      = member.getId().asString();
        String linkedMcName   = manager.getLinkedMinecraftName(discordId);
        String claimName      = linkedMcName != null ? linkedMcName : member.getDisplayName();
        boolean ok = manager.claimSupportFromDiscord(discordId, claimName, playerName, false);
        if (ok) {
            claimedByUserId.put(channelId, member.getId().asString());
            claimedByName.put(channelId, claimName);
            discordStaffSession.put(member.getId().asString(), sessionId);
            updateChannelToClaimed(sessionId, claimName, member.getId().asString());
            event.createFollowup("You have claimed this support request.").withEphemeral(true).subscribe();
        } else {
            event.createFollowup("Could not claim the support request.").withEphemeral(true).subscribe();
        }
    }

    private void handleCloseButton(ButtonInteractionEvent event, Member member) {
        String channelId = event.getCustomId().substring(6);
        String claimerUserId = claimedByUserId.get(channelId);

        if (claimerUserId == null || !claimerUserId.equals(member.getId().asString())) {
            if (!hasManagementRole(member)) {
                event.createFollowup(config.getDiscordSettings().onlyClaimerCanClose).withEphemeral(true).subscribe();
                return;
            }
        }

        boolean ok = manager.closeSupportSessionFromDiscord(member.getId().asString());
        if (!ok) {
            // Try with claimerUserId if member is management overriding
            ok = claimerUserId != null && manager.closeSupportSessionFromDiscord(claimerUserId);
        }
        event.createFollowup(ok ? "Support session closed." : "Could not close the session.").withEphemeral(true).subscribe();
    }

    // -------------------------------------------------------------------------
    // Message create (Discord -> Minecraft relay)
    // -------------------------------------------------------------------------

    private void onMessageCreate(MessageCreateEvent event) {
        try {
            Message msg = event.getMessage();
            if (msg.getAuthor().map(discord4j.core.object.entity.User::isBot).orElse(true)) return;

            TextChannel channel = (TextChannel) msg.getChannel().block();
            if (channel == null) return;

            String channelId = channel.getId().asString();
            String sessionId = channelToSession.get(channelId);
            if (sessionId == null) return;

            if (!claimedByUserId.containsKey(channelId)) {
                msg.delete().subscribe();
                channel.createMessage(config.getDiscordSettings().pleaseClaimMessage).subscribe();
                return;
            }

            Member member = msg.getAuthorAsMember().block();
            if (member == null) return;

            manager.handleSupportChatFromDiscord(member.getId().asString(), msg.getContent());
        } catch (Exception e) {
            plugin.getLogger().error("Error processing Discord message.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Slash commands
    // -------------------------------------------------------------------------

    private void onSlashCommand(ChatInputInteractionEvent event) {
        if (!"stats".equals(event.getCommandName())) return;

        Member member = event.getInteraction().getMember().orElse(null);
        if (member == null || !hasStaffRole(member)) {
            event.reply("You do not have permission to use this command.").withEphemeral(true).subscribe();
            return;
        }

        boolean visible = event.getOption("visible")
                .flatMap(opt -> opt.getValue())
                .map(v -> v.asBoolean())
                .orElse(false);

        int total = manager.getTotalRatedCount();
        if (total == 0) {
            event.reply("No ratings have been submitted yet.").withEphemeral(true).subscribe();
            return;
        }

        event.deferReply().withEphemeral(!visible).block();
        try {
            Map<String, SupportManager.StaffStats> staffStats = manager.buildStaffStats();
            double avg = manager.getGlobalAverageRating();

            File chart = StatsChartGenerator.generate(staffStats, avg, total, plugin.getLogger());
            if (chart != null) {
                event.editReply()
                        .withContentOrNull(null)
                        .block();
                event.createFollowup()
                        .withEphemeral(!visible)
                        .withFiles(discord4j.core.spec.MessageCreateFields.File.of(
                                "stats.png", Files.newInputStream(chart.toPath())))
                        .block();
                chart.delete();
            } else {
                event.editReply().withContent("Failed to generate chart.").block();
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling /stats command.", e);
            event.editReply().withContent("Failed to load statistics.").block();
        }
    }

    // -------------------------------------------------------------------------
    // Public API for SupportManager
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
                    .addField("Player",   playerName,              true)
                    .addField("Server",   serverName,              true)
                    .addField("Language", language.toUpperCase(),  true)
                    .addField("Status",   "Open",                  true)
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
                        .addField("Player",  channelToPlayer.getOrDefault(chId, "?"), true)
                        .addField("Staff",   staffName,                               true)
                        .addField("Status",  "Claimed",                               true)
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
                    .addField("Player",       playerName,      true)
                    .addField("Staff Member", staffName,       true)
                    .addField("Rating",       rating + " / 5", true)
                    .timestamp(Instant.now())
                    .build();

            channel.createMessage(MessageCreateSpec.builder().addEmbed(embed).build()).subscribe();
        } catch (Exception e) {
            plugin.getLogger().error("Failed to send rating embed.", e);
        }
    }

    // Kept for compatibility
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

            List<Message> messages = channel.getMessagesBefore(Snowflake.of(Instant.now())).collectList().block();
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
    // Helpers
    // -------------------------------------------------------------------------

    private boolean hasStaffRole(Member member) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        List<String> roles = member.getRoles().map(r -> r.getId().asString()).collectList().block();
        return roles != null && roles.stream().anyMatch(r -> r.equals(cfg.staffRoleId) || r.equals(cfg.managementRoleId));
    }

    private boolean hasManagementRole(Member member) {
        SupportConfig.ConfigData.DiscordSettings cfg = config.getDiscordSettings();
        List<String> roles = member.getRoles().map(r -> r.getId().asString()).collectList().block();
        return roles != null && roles.stream().anyMatch(r -> r.equals(cfg.managementRoleId));
    }

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
