package net.gravijet.velocity.core.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerLogger {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLogger.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path logDirectory;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "player-logger-io");
        t.setDaemon(true);
        return t;
    });

    public PlayerLogger(Path dataDirectory) {
        this.logDirectory = dataDirectory.resolve("player_logs");
        try {
            Files.createDirectories(logDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create player_logs directory", e);
        }
    }

    public void logJoin(String playerName, String ip, String server) {
        log(playerName, playerName + " JOINED [" + server + "] (" + ip + ")");
    }

    public void logSwitch(String playerName, String ip, String fromServer, String toServer) {
        log(playerName, playerName + " SWITCHED [" + fromServer + " -> " + toServer + "] (" + ip + ")");
    }

    public void logLeave(String playerName, String ip, String server) {
        log(playerName, playerName + " LEFT [" + server + "] (" + ip + ")");
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void log(String playerName, String message) {
        String line = "[" + LocalDateTime.now().format(FORMATTER) + "] " + message + System.lineSeparator();
        // Minecraft usernames are [a-zA-Z0-9_]; sanitize defensively in case of unusual proxy config.
        String safeName = playerName.replace('/', '_').replace('\\', '_').replace(':', '_');
        Path logFile = logDirectory.resolve(safeName + ".log");
        ioExecutor.execute(() -> {
            try {
                Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warn("Failed to write player log for {}: {}", playerName, e.getMessage());
            }
        });
    }
}
