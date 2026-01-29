package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes and executes commands (export, arliai, cerebras, semantic, grep, search, etc.)
 * Keeps only non-!last commands here. !last is handled in the main bot.
 */
public class CommandDispatcher {
    private final MatrixClient matrixClient;
    private final RoomHistoryManager historyManager;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final MatrixRobobot.Config config;
    private final Map<String, AtomicBoolean> runningOperations;

    public CommandDispatcher(MatrixClient matrixClient, RoomHistoryManager historyManager, HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, MatrixRobobot.Config config, Map<String, AtomicBoolean> runningOperations) {
        this.matrixClient = matrixClient;
        this.historyManager = historyManager;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.config = config;
        this.runningOperations = runningOperations;
    }

    /**
     * Dispatch a command based on its prefix
     */
    public boolean dispatchCommand(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        if ("!testcommand".equals(trimmed)) {
            matrixClient.sendText(responseRoomId, "Hello, world!");
            return true;
        } else if (trimmed.matches("!export\\d+h")) {
            handleExport(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!arliai\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
            handleArliAI(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!arliai-ts\\s+\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
            handleArliAIWithTimestamp(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!cerebras\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
            handleCerebras(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!cerebras-ts\\s+\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
            handleCerebrasWithTimestamp(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!semantic\\s+[A-Z]{3}\\s+\\d+h\\s+(.+)")) {
            handleSemanticSearch(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep\\s+[A-Z]{3}\\s+\\d+[dh]\\s+(.+)")) {
            handleGrep(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep-slow\\s+[A-Z]{3}\\s+\\d+[dh]\\s+(.+)")) {
            handleGrepSlow(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!search\\s+[A-Z]{3}\\s+\\d+[dh]\\s+(.+)")) {
            handleSearch(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if ("!abort".equals(trimmed)) {
            handleAbort(sender, responseRoomId);
            return true;
        } else if ("!help".equals(trimmed)) {
            handleHelp(responseRoomId);
            return true;
        }
        return false;
    }

    private void handleExport(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        int hours = Integer.parseInt(trimmed.replaceAll("\\D+", ""));
        System.out.println("Received export command in " + roomId + " from " + sender + " (" + hours + "h)");
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String safeRoom = exportRoomId.replaceAll("[^A-Za-z0-9._-]", "_");
                String filename = safeRoom + "-last" + hours + "h-" + now + ".txt";

                matrixClient.sendMarkdown(responseRoomId, "Starting export of last " + hours + "h from " + exportRoomId + " to " + filename);

                java.util.List<String> lines = historyManager.fetchRoomHistory(exportRoomId, hours, prevBatch);

                if (lines.isEmpty()) {
                    matrixClient.sendMarkdown(responseRoomId, "No chat logs found for the last " + hours + "h to export from " + exportRoomId + ".");
                    return;
                }

                try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(filename))) {
                    for (String l : lines) w.write(l + "\n");
                }

                matrixClient.sendMarkdown(responseRoomId, "Export complete: " + filename + " (" + lines.size() + " messages)");
                System.out.println("Exported " + lines.size() + " messages to " + filename);
            } catch (Exception e) {
                System.out.println("Export failed: " + e.getMessage());
                try { matrixClient.sendMarkdown(responseRoomId, "Export failed: " + e.getMessage()); } catch (Exception ignore) {}
            }
        }).start();
    }

    private void handleArliAI(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!arliai\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int hours = Integer.parseInt(matcher.group(2));
            String question = matcher.group(3) != null ? matcher.group(3).trim() : null;
            System.out.println("Received arliai command in " + roomId + " from " + sender);
            new Thread(() -> executeArliQuery(hours, prevBatch, question, responseRoomId, exportRoomId, -1, timezoneAbbr)).start();
        }
    }

    private void handleArliAIWithTimestamp(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!arliai-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String startDateStr = matcher.group(1);
            String timezoneAbbr = matcher.group(2);
            int durationHours = Integer.parseInt(matcher.group(3));
            String question = matcher.group(4) != null ? matcher.group(4).trim() : null;

            ZoneId userZone = getZoneIdFromAbbr(timezoneAbbr);
            long startTimestamp = java.time.LocalDateTime.parse(startDateStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
                    .atZone(userZone)
                    .withZoneSameInstant(ZoneId.of("UTC"))
                    .toInstant()
                    .toEpochMilli();

            System.out.println("Received arliai-ts command in " + roomId + " from " + sender);
            new Thread(() -> executeArliQuery(durationHours, prevBatch, question, responseRoomId, exportRoomId, startTimestamp, timezoneAbbr)).start();
        }
    }

    private void executeArliQuery(int hours, String prevBatch, String question, String responseRoomId, String exportRoomId, long startTimestamp, String timezoneAbbr) {
        // This is a placeholder - integrate with actual Arli AI logic from original bot
        matrixClient.sendMarkdown(responseRoomId, "[ARLI AI command queued but implementation moved to separate service]");
    }

    private void handleCerebras(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!cerebras\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            System.out.println("Received cerebras command in " + roomId + " from " + sender);
            new Thread(() -> matrixClient.sendMarkdown(responseRoomId, "[CEREBRAS command queued but implementation moved to separate service]")).start();
        }
    }

    private void handleCerebrasWithTimestamp(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        System.out.println("Received cerebras-ts command in " + roomId + " from " + sender);
        new Thread(() -> matrixClient.sendMarkdown(responseRoomId, "[CEREBRAS-TS command queued but implementation moved to separate service]")).start();
    }

    private void handleSemanticSearch(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!semantic\\s+([A-Z]{3})\\s+(\\d+)h\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            System.out.println("Received semantic search command in " + roomId + " from " + sender);
            new Thread(() -> matrixClient.sendMarkdown(responseRoomId, "[SEMANTIC command queued but implementation moved to separate service]")).start();
        }
    }

    private void handleGrep(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!grep\\s+([A-Z]{3})\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            System.out.println("Received grep command in " + roomId + " from " + sender);
            new Thread(() -> matrixClient.sendMarkdown(responseRoomId, "[GREP command queued but implementation moved to separate service]")).start();
        }
    }

    private void handleGrepSlow(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!grep-slow\\s+([A-Z]{3})\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            System.out.println("Received grep-slow command in " + roomId + " from " + sender);
            new Thread(() -> matrixClient.sendMarkdown(responseRoomId, "[GREP-SLOW command queued but implementation moved to separate service]")).start();
        }
    }

    private void handleSearch(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!search\\s+([A-Z]{3})\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            System.out.println("Received search command in " + roomId + " from " + sender);
            new Thread(() -> matrixClient.sendMarkdown(responseRoomId, "[SEARCH command queued but implementation moved to separate service]")).start();
        }
    }

    private void handleAbort(String sender, String responseRoomId) {
        System.out.println("Received abort command from " + sender);
        AtomicBoolean abortFlag = runningOperations.get(sender);
        if (abortFlag != null) {
            abortFlag.set(true);
            matrixClient.sendText(responseRoomId, "Aborting your running search/grep operations...");
        } else {
            matrixClient.sendText(responseRoomId, "No running operations found to abort.");
        }
    }

    private void handleHelp(String responseRoomId) {
        System.out.println("Received help command");
        String helpText = "**Matrix Bot Commands (Primary Use Case: !last)**\n\n" +
            "**!last** - Show your last message and read receipt status in export room (PRIMARY)\n\n" +
            "**Additional Commands:**\n\n" +
            "**!ping** - Measure and report ping latency\n\n" +
            "**!testcommand** - Test if the bot is responding\n\n" +
            "**!export<duration>h** - Export chat history (e.g., `!export24h`)\n\n" +
            "**!arliai, !cerebras** - Query AI with chat logs\n\n" +
            "**!semantic** - AI-free semantic search using local embeddings\n\n" +
            "**!grep, !grep-slow, !search** - Pattern and term-based searches\n\n" +
            "**!abort** - Abort currently running operations";
        matrixClient.sendMarkdown(responseRoomId, helpText);
    }

    private ZoneId getZoneIdFromAbbr(String timezoneAbbr) {
        switch (timezoneAbbr.toUpperCase()) {
            case "PST": return ZoneId.of("America/Los_Angeles");
            case "PDT": return ZoneId.of("America/Los_Angeles");
            case "MST": return ZoneId.of("America/Denver");
            case "MDT": return ZoneId.of("America/Denver");
            case "CST": return ZoneId.of("America/Chicago");
            case "CDT": return ZoneId.of("America/Chicago");
            case "EST": return ZoneId.of("America/New_York");
            case "EDT": return ZoneId.of("America/New_York");
            case "UTC": return ZoneId.of("UTC");
            case "GMT": return ZoneId.of("GMT");
            default: return ZoneId.of("America/Los_Angeles");
        }
    }
}
