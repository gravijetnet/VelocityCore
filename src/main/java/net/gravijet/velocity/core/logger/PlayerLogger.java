package net.gravijet.velocity.core.logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PlayerLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path logDirectory;

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

    private void log(String playerName, String message) {
        String line = "[" + LocalDateTime.now().format(FORMATTER) + "] " + message + System.lineSeparator();
        Path logFile = logDirectory.resolve(playerName + ".log");
        try {
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
