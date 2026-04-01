package net.gravijet.velocity.core.util;

import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.Main;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class ConfigManager {

    private final ProxyServer server;
    private final Path dataDirectory;
    private CommentedConfigurationNode config;
    private CommentedConfigurationNode messages;

    public ConfigManager(ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        load();
    }

    public void load() {
        config = loadYamlConfiguration("config.yml");
        messages = loadYamlConfiguration("messages.yml");
    }

    public CommentedConfigurationNode getConfig() {
        return config;
    }

    public CommentedConfigurationNode getMessages() {
        return messages;
    }

    private CommentedConfigurationNode loadYamlConfiguration(String fileName) {
        Path filePath = dataDirectory.resolve(fileName);
        if (Files.notExists(filePath)) {
            // Use an absolute path to get the resource from the root of the JAR
            try (InputStream in = Main.class.getResourceAsStream("/" + fileName)) {
                if (in == null) {
                    throw new IOException("Resource not found: " + fileName);
                }
                Files.createDirectories(filePath.getParent());
                Files.copy(in, filePath);
            } catch (IOException e) {
                server.getConsoleCommandSource().sendMessage(net.kyori.adventure.text.Component.text("Could not create " + fileName + ": " + e.getMessage(), net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(filePath)
                .build();
        try {
            return loader.load();
        } catch (ConfigurateException e) {
            server.getConsoleCommandSource().sendMessage(net.kyori.adventure.text.Component.text("Error loading " + fileName + ": " + e.getMessage(), net.kyori.adventure.text.format.NamedTextColor.RED));
            return null;
        }
    }
}