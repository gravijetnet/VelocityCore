package net.gravijet.velocity.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class CoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(CoreConfig.class);

    private final File configFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private Messages messages;

    public CoreConfig(Path dataDirectory) {
        dataDirectory.toFile().mkdirs();
        this.configFile = dataDirectory.resolve("core-messages.json").toFile();
        load();
    }

    public void load() {
        if (!configFile.exists()) {
            messages = new Messages();
            save();
        } else {
            try {
                messages = mapper.readValue(configFile, Messages.class);
            } catch (IOException e) {
                logger.error("Failed to load core-messages.json, using defaults.", e);
                messages = new Messages();
            }
        }
    }

    public void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, messages);
        } catch (IOException e) {
            logger.error("Failed to save core-messages.json.", e);
        }
    }

    public Messages get() {
        return messages;
    }

    public static class Messages {

        // ── General ──────────────────────────────────────────────────────────
        public String playersOnly    = "&cThis command can only be used by players.";
        public String noPermission   = "&cYou do not have permission.";
        public String playerNotFound = "&cPlayer &f{player} &cnot found.";
        public String invalidAmount  = "&cAmount must be a positive number.";

        // ── JoinMe ────────────────────────────────────────────────────────────
        public String joinMeNoPerm     = "&cYou don't have permission to use JoinMe.";
        public String joinMeCooldown   = "&cCooldown active. &f{seconds}s &cremaining.";
        public String joinMeNoTokens   = "&cNo tokens left. Check your balance with &f/tokens&c.";
        public String joinMeTokenError = "&cFailed to use token. Please try again.";
        public String joinMeBroadcast  = "&8&l» &c{player} &7is inviting you to join &f{server}&7.";
        public String joinMeHover      = "&7Click to join &f{server}&7.";

        // ── JoinMe Color ──────────────────────────────────────────────────────
        public String joinMeColorUsage = "&cUsage: /joinmecolor <&code | &#RRGGBB | reset>";
        public String joinMeColorReset = "&fJoinMe color reset to default.";
        public String joinMeColorSet   = "&fJoinMe color set to {preview}&r&f.";

        // ── Tokens ────────────────────────────────────────────────────────────
        public List<String> tokensInfo = Arrays.asList(
                "&c&lGraviJet &7\u00bb &f&lJoinMe Tokens",
                "&cStatus&8:    &f{status}",
                "&cTotal&8:     &f{total}",
                "&cMonthly&8:   &f{monthly} &8(&7resets monthly&8)",
                "&cPermanent&8: &f{permanent} &8(&7never expires&8)",
                "&cCooldown&8:  &f{cooldown}"
        );
        public String tokensStoreLink        = "&7Buy more tokens at &example.invalid&7.";
        public String tokensError            = "&cFailed to load token data.";
        public String tokensStatusAvailable  = "Available";
        public String tokensStatusEmpty      = "Out of tokens";
        public String tokensStatusUnlimited  = "Unlimited";
        public String tokensCooldownBypassed = "Bypassed";
        public String tokensCooldownDefault  = "5 minutes";

        // ── Admin JoinMe ──────────────────────────────────────────────────────
        public List<String> adminJoinMeHelp = Arrays.asList(
                "&c&lGraviJet &7\u00bb &f&lAdmin JoinMe &8- &7Commands",
                "&4\u25cf &c/adminjoinme tokens <player> &7\u00bb &fView token details",
                "&4\u25cf &c/adminjoinme forcejoinme <player> &7\u00bb &fForce a player's JoinMe",
                "&4\u25cf &c/adminjoinme addtokens <player> <amount> &7\u00bb &fAdd permanent tokens",
                "&4\u25cf &c/adminjoinme removetokens <player> <amount> &7\u00bb &fRemove permanent tokens"
        );
        public String adminTokensHeader   = "&c&lGraviJet &7\u00bb &f&lTokens &8\u2014 &f{player}";
        public String adminForceSuccess   = "&fJoinMe forced for &c{player}&f.";
        public String adminForceReceived  = "&fAn admin forced a JoinMe for you.";
        public String adminAddSuccess     = "&fAdded &c{amount} &fpermanent tokens to &c{player}&f.";
        public String adminAddReceived    = "&f{amount} &cpermanent tokens &fadded to your account.";
        public String adminRemoveSuccess  = "&fRemoved &c{amount} &fpermanent tokens from &c{player}&f.";
        public String adminRemoveReceived = "&c{amount} &fpermanent tokens &fremoved from your account.";
        public String adminAddFailed      = "&cFailed to add tokens.";
        public String adminRemoveFailed   = "&cFailed to remove tokens.";
        public String adminAmountClamped  = "&7Note: amount clamped to maximum.";

        // ── Ping ──────────────────────────────────────────────────────────────
        public String pingSelf    = "&8\u00bb &7Ping&8: {color}{ping}ms";
        public String pingOther   = "&8\u00bb &7Ping &8- &f{player}&8: {color}{ping}ms";
        public String pingConsole = "&cConsole must specify a player.";

        // ── Find ──────────────────────────────────────────────────────────────
        public String findUsage   = "&cUsage: /find <player>";
        public String findOnline  = "&f{player} &7is online on &c{server}&7.";
        public String findOffline = "&f{player} &7was last seen on &c{server} &7at &f{time}&7.";
    }
}
