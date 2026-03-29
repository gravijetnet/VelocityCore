package net.gravijet.velocity.core.support.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class SupportConfig {
    private final Path dataDirectory;
    private final File configFile;
    private final ObjectMapper mapper;
    private ConfigData configData;
    private static final Logger logger = LoggerFactory.getLogger(SupportConfig.class);

    public SupportConfig(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.configFile = new File(dataDirectory.toFile(), "config.json");
        this.mapper = new ObjectMapper();
        load();
    }

    public void load() {
        if (!configFile.exists()) {
            dataDirectory.toFile().mkdirs();
            configData = new ConfigData();
            save();
            logger.info("Created default configuration.");
        } else {
            try {
                configData = mapper.readValue(configFile, ConfigData.class);
                logger.info("Configuration loaded.");
            } catch (IOException e) {
                logger.error("Failed to load configuration, using defaults.", e);
                configData = new ConfigData();
            }
        }
    }

    public void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, configData);
        } catch (IOException e) {
            logger.error("Failed to save configuration.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Inner data classes
    // -------------------------------------------------------------------------

    public static class ConfigData {
        public DiscordSettings discord = new DiscordSettings();
        public GermanMessages de = new GermanMessages();
        public Permissions permissions = new Permissions();

        public List<String> mainCommandMessage = Arrays.asList(
                "&c&lGraviJet &7» &f&lSupport &8- &7Usage",
                "&4● &c/support <language> &7» &fOpen a request in your prefered language. (DE/EN)",
                "&4● &c/support chat <message> &7» &fSend a message in your session. &8(&c/spc&8)",
                "&4● &c/support rate <1-5> &7» &fRate your support session."
                );

        public List<String> staffHelpMessage = Arrays.asList(
                "&c&lGraviJet &7» &f&lSupport &8- &7Usage",
                "&4● &c/support claim <player> [--force] &7» &fClaim a support request.",
                "&4● &c/support chat <message> &7» &fSend a message in the session. &8(&c/spc&8)",
                "&4● &c/support close &7» &fClose the current session.",
                "&4● &c/support show <player> &7» &fView session details.",
                "&4● &c/support transfer <staff> &7» &fTransfer the session.",
                "&4● &c/support setlanguage <language> &7» &fChange the session language.",
                "&4● &c/support ban <player> [duration] &7» &fBan a player from support.",
                "&4● &c/support unban <player> &7» &fUnban a player from support.",
                "&4● &c/support link <player> <discordId> &7» &fLink a player to their Discord account.",
                "&4● &c/support unlink <player> &7» &fRemove a Discord link."
        );

        // English messages
        public String invalidLanguage            = "&cUsage: /support de &8| &c/support en";
        public String supportRequestSent         = "&7Your request has been sent. A staff member will respond shortly.";
        public String supportRequestReceived     = "&7New support request from &f{player} &7on &f{server} &8({language})";
        public String noActiveSupport            = "&cYou do not have an active support session.";
        public String alreadyInSession           = "&cYou already have an open support request.";
        public String supportAlreadyClaimed      = "&cThis session is already being handled by &f{staff}&c.";
        public String supportClaimed             = "&7You are now assisting &f{player}&7.";
        public String supportClaimedPlayer       = "&7A staff member has joined your session.";
        public String chatFormatStaff            = "&8[&7Support&8] &f{player}&7: {message}";
        public String chatFormatPlayer           = "&8[&7Support&8] &7You&8: &f{message}";
        public String chatFormatPlayerStaff      = "&8[&7Support&8] &7Staff&8: &f{message}";
        public String supportClosedStaff         = "&7Session with &f{player} &7has been closed.";
        public String supportClosedPlayer        = "&7Your support session has been closed. Thanks for reaching out.";
        public String supportAutoClosedPlayer    = "&7Your session was closed due to inactivity.";
        public String ratingReceived             = "&7Thanks for your rating of &f{rating}/5&7.";
        public String invalidRating              = "&cPlease enter a number between 1 and 5.";
        public String alreadyRated               = "&cYou have already rated this session.";
        public String ratingExpired              = "&cRatings are only available within one hour of the session closing.";
        public String notSessionPlayer           = "&cOnly the player who opened this session can rate it.";
        public String ratingPrompt               = "&7How was your support experience? Use &f/support rate <1-5> &7to leave a rating.";
        public String playerBanned               = "&f{player} &7has been banned from support for &f{duration}&7.";
        public String playerUnbanned             = "&f{player}&7 can now use support again.";
        public String youAreBanned               = "&cYou are banned from using support.{timeLeft}";
        public String youAreUnbanned             = "&7You can now use the support system again.";
        public String noPermission               = "&cYou do not have permission to do that.";
        public String playerNotFound             = "&cNo player found with that name.";
        public String invalidDuration            = "&cInvalid duration format. Examples: &f30m&c, &f2h&c, &f1d&c, &fperm";
        public String staffCannotRequest         = "&cStaff members cannot open support requests.";
        public String supportTransferred         = "&7Session transferred to &f{staff}&7.";
        public String supportTransferredStaff    = "&7The session with &f{player} &7has been transferred to you.";
        public String supportShowHeader          = "&8&m     &r &7Session: &f{player} &8&m     ";
        public String supportShowLanguage        = "&7Language&8: &f{language}";
        public String supportShowStaff           = "&7Staff&8:    &f{staff}";
        public String supportShowCreated         = "&7Opened&8:   &f{time}";
        public String languageSet                = "&7Session language changed to &f{language}&7.";
        public String languageSetPlayer          = "&7The session language has been changed to &f{language}&7.";
        public String playerOnlineStatus         = "&f{player} &7is back online.";
        public String playerOfflineStatus        = "&f{player} &7has gone offline.";

        public static class GermanMessages {
            public String alreadyInSession        = "&cDu hast bereits eine offene Support-Anfrage.";
            public String supportRequestSent      = "&7Deine Anfrage wurde gesendet. Ein Teammitglied meldet sich gleich.";
            public String noActiveSupport         = "&cDu hast keine aktive Support-Anfrage.";
            public String supportClaimedPlayer    = "&7Ein Teammitglied hat deine Sitzung angenommen.";
            public String chatFormatPlayer        = "&8[&7Support&8] &7Du&8: &f{message}";
            public String chatFormatPlayerStaff   = "&8[&7Support&8] &7Team&8: &f{message}";
            public String supportClosedPlayer     = "&7Deine Support-Anfrage wurde geschlossen. Danke.";
            public String supportAutoClosedPlayer = "&7Deine Anfrage wurde wegen Inaktivität geschlossen.";
            public String ratingReceived          = "&7Danke für deine Bewertung von &f{rating}/5&7.";
            public String invalidRating           = "&cBitte gib eine Zahl zwischen 1 und 5 an.";
            public String alreadyRated            = "&cDu hast diese Anfrage bereits bewertet.";
            public String ratingExpired           = "&cBewertungen sind nur innerhalb einer Stunde nach Schliessen möglich.";
            public String notSessionPlayer        = "&cNur der Spieler, der die Anfrage gestellt hat, kann sie bewerten.";
            public String ratingPrompt            = "&7Wie war dein Support? Bewerte uns mit &f/support rate <1-5>&7.";
            public String youAreBanned            = "&cDu bist vom Support gesperrt.{timeLeft}";
            public String youAreUnbanned          = "&7Du kannst den Support wieder nutzen.";
            public String noPermission            = "&cDazu hast du keine Berechtigung.";
            public String staffCannotRequest      = "&cTeammitglieder können keinen Support anfordern.";
            public String languageSetPlayer       = "&7Die Sprache wurde auf &f{language} &7geändert.";
        }

        public static class DiscordSettings {
            public String botToken              = "YOUR_BOT_TOKEN";
            public String allowedGuildId        = "YOUR_GUILD_ID";
            public String supportCategoryId     = "YOUR_CATEGORY_ID";
            public String transcriptChannelId   = "YOUR_TRANSCRIPT_CHANNEL_ID";
            public String staffRoleId           = "YOUR_STAFF_ROLE_ID";
            public String managementRoleId      = "YOUR_MANAGEMENT_ROLE_ID";
            public String claimMessage          = "Claimed by {staff}.";
            public String transferMessage       = "Session transferred from {fromStaff} to {toStaff}.";
            public String statusMessage         = "{status}";
            public String channelNameFormat     = "{status}-{language}-{player}-{server}";
            public String openColor             = "RED";
            public String claimedColor          = "GREEN";
            public String pleaseClaimMessage    = "Please claim this request before sending messages.";
            public String alreadyClaimedMessage = "This request is already being handled by {staff}.";
            public String onlyClaimerCanClose   = "Only the staff member who claimed this request can close it.";
            public String transcriptTitle       = "Support Transcript";
            public String transcriptPlayerField = "Player";
            public String transcriptLanguageField = "Language";
            public String transcriptDateField   = "Date";
        }

        public static class Permissions {
            public String supportRequests  = "support.requests";
            public String supportClaim     = "support.claim";
            public String supportChat      = "support.chat";
            public String supportClose     = "support.close";
            public String supportRate      = "support.rate";
            public String supportBan       = "support.ban";
            public String supportUnban     = "support.unban";
            public String supportTransfer  = "support.transfer";
            public String supportShow      = "support.show";
            public String supportForce     = "support.force";
            public String supportSetLanguage = "support.setlanguage";
            public String supportLink      = "support.link";
            public String supportAll       = "support.*";
        }
    }

    // -------------------------------------------------------------------------
    // Getters - delegate to configData
    // -------------------------------------------------------------------------

    public ConfigData.DiscordSettings getDiscordSettings() { return configData.discord; }
    public ConfigData.Permissions getPermissions() { return configData.permissions; }
    public List<String> getMainCommandMessage() { return configData.mainCommandMessage; }
    public List<String> getStaffHelpMessage() { return configData.staffHelpMessage; }

    private boolean de(String lang) { return "de".equalsIgnoreCase(lang); }

    public String getInvalidLanguage()     { return configData.invalidLanguage; }
    public String getSupportRequestSent(String lang) { return de(lang) ? configData.de.supportRequestSent : configData.supportRequestSent; }
    public String getSupportRequestReceived() { return configData.supportRequestReceived; }
    public String getNoActiveSupport(String lang)   { return de(lang) ? configData.de.noActiveSupport : configData.noActiveSupport; }
    public String getAlreadyInSession(String lang)  { return de(lang) ? configData.de.alreadyInSession : configData.alreadyInSession; }
    public String getSupportAlreadyClaimed()   { return configData.supportAlreadyClaimed; }
    public String getSupportClaimed()          { return configData.supportClaimed; }
    public String getSupportClaimedPlayer(String lang) { return de(lang) ? configData.de.supportClaimedPlayer : configData.supportClaimedPlayer; }
    public String getChatFormatStaff()         { return configData.chatFormatStaff; }
    public String getChatFormatPlayer(String lang) { return de(lang) ? configData.de.chatFormatPlayer : configData.chatFormatPlayer; }
    public String getChatFormatPlayerStaff(String lang) { return de(lang) ? configData.de.chatFormatPlayerStaff : configData.chatFormatPlayerStaff; }
    public String getSupportClosedStaff()      { return configData.supportClosedStaff; }
    public String getSupportClosedPlayer(String lang) { return de(lang) ? configData.de.supportClosedPlayer : configData.supportClosedPlayer; }
    public String getSupportAutoClosedPlayer(String lang) { return de(lang) ? configData.de.supportAutoClosedPlayer : configData.supportAutoClosedPlayer; }
    public String getRatingReceived(String lang) { return de(lang) ? configData.de.ratingReceived : configData.ratingReceived; }
    public String getInvalidRating(String lang)  { return de(lang) ? configData.de.invalidRating : configData.invalidRating; }
    public String getAlreadyRated(String lang)   { return de(lang) ? configData.de.alreadyRated : configData.alreadyRated; }
    public String getRatingExpired(String lang)  { return de(lang) ? configData.de.ratingExpired : configData.ratingExpired; }
    public String getNotSessionPlayer(String lang) { return de(lang) ? configData.de.notSessionPlayer : configData.notSessionPlayer; }
    public String getRatingPrompt(String lang)   { return de(lang) ? configData.de.ratingPrompt : configData.ratingPrompt; }
    public String getPlayerBanned()              { return configData.playerBanned; }
    public String getPlayerUnbanned()            { return configData.playerUnbanned; }
    public String getYouAreBanned(String lang, String timeLeft) {
        String msg = de(lang) ? configData.de.youAreBanned : configData.youAreBanned;
        return msg.replace("{timeLeft}", timeLeft.isEmpty() ? "" : " " + timeLeft);
    }
    public String getYouAreUnbanned(String lang) { return de(lang) ? configData.de.youAreUnbanned : configData.youAreUnbanned; }
    public String getNoPermission(String lang)   { return de(lang) ? configData.de.noPermission : configData.noPermission; }
    public String getPlayerNotFound()            { return configData.playerNotFound; }
    public String getInvalidDuration()           { return configData.invalidDuration; }
    public String getStaffCannotRequest(String lang) { return de(lang) ? configData.de.staffCannotRequest : configData.staffCannotRequest; }
    public String getSupportTransferred()        { return configData.supportTransferred; }
    public String getSupportTransferredStaff()   { return configData.supportTransferredStaff; }
    public String getSupportShowHeader()         { return configData.supportShowHeader; }
    public String getSupportShowLanguage()       { return configData.supportShowLanguage; }
    public String getSupportShowStaff()         { return configData.supportShowStaff; }
    public String getSupportShowCreated()        { return configData.supportShowCreated; }
    public String getLanguageSet()               { return configData.languageSet; }
    public String getLanguageSetPlayer(String lang) { return de(lang) ? configData.de.languageSetPlayer : configData.languageSetPlayer; }
    public String getPlayerOnlineStatus()        { return configData.playerOnlineStatus; }
    public String getPlayerOfflineStatus()       { return configData.playerOfflineStatus; }
}
