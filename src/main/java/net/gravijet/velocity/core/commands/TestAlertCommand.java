package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestAlertCommand implements SimpleCommand {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+"
    );

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();



        String text = String.join(" ", args);
        String raw = "&8[&4Alert&8] &c" + text;

        if (!(source instanceof Player player)) {
            source.sendMessage(legacy(raw));
            return;
        }

        if (args.length == 0) {
            player.sendMessage(legacy("&cUsage: &f/testalert <text>"));
            return;
        }

        player.sendMessage(buildWithLinks(raw));
    }

    /**
     * Parses legacy {@code &} color codes and wraps detected URLs in click events,
     * preserving {@code &} color formatting on the URL text itself.
     */
    private Component buildWithLinks(String raw) {
        Component result = Component.empty();
        Matcher matcher = URL_PATTERN.matcher(raw);
        int lastEnd = 0;

        while (matcher.find()) {
            // Text before this URL
            if (matcher.start() > lastEnd) {
                result = result.append(
                        legacy(raw.substring(lastEnd, matcher.start()))
                );
            }

            // The URL itself — show it with the color codes that may surround it,
            // but also make it clickable (use plain text for the click label so
            // ampersands in the URL aren't misinterpreted).
            String url = matcher.group();
            result = result.append(
                    legacy(url).clickEvent(ClickEvent.openUrl(url))
            );

            lastEnd = matcher.end();
        }

        // Remaining text after the last URL
        if (lastEnd < raw.length()) {
            result = result.append(legacy(raw.substring(lastEnd)));
        }

        return result;
    }

    /**
     * Shorthand for deserializing a {@code &}-color-coded string into a
     * native Adventure {@link Component}.
     */
    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.testalert");
    }
}
