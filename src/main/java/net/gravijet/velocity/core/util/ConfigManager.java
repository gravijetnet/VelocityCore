package net.gravijet.velocity.core.util;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final Path dataDirectory;
    private final Logger logger;
    private final ProxyServer server;
    private Map<String, Object> config;

    public ConfigManager(ProxyServer server, Logger logger, Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        loadConfig();
    }

    public void loadConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            Path configFile = dataDirectory.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    } else {
                        Files.createFile(configFile);
                    }
                }
            }
            try (InputStream in = Files.newInputStream(configFile)) {
                config = new Yaml().load(in);
                if (config == null) {
                    config = new java.util.HashMap<>();
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load config.yml", e);
            config = new java.util.HashMap<>();
        }
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        return (boolean) getObject(path, defaultValue);
    }

    public int getInt(String path, int defaultValue) {
        Number value = (Number) getObject(path, defaultValue);
        return value.intValue();
    }

    public String getString(String path, String defaultValue) {
        return (String) getObject(path, defaultValue);
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path, List<String> defaultValue) {
        return (List<String>) getObject(path, defaultValue);
    }

    @SuppressWarnings("unchecked")
    private Object getObject(String path, Object defaultValue) {
        if (config == null) return defaultValue;
        String[] pathParts = path.split("\\.");
        Map<String, Object> current = config;
        for (int i = 0; i < pathParts.length; i++) {
            String part = pathParts[i];
            if (current.containsKey(part)) {
                if (i == pathParts.length - 1) {
                    return current.get(part);
                } else {
                    Object next = current.get(part);
                    if (next instanceof Map) {
                        current = (Map<String, Object>) next;
                    } else {
                        return defaultValue;
                    }
                }
            } else {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
