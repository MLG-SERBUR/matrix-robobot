package com.robomwm.ai.matrixrobobot;

import java.time.ZoneId;
import java.net.http.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes and executes commands (export, arliai, cerebras, semantic, grep,
 * search, etc.)
 * Keeps only non-!last commands here. !last is handled in the main bot.
 */
public class CommandDispatcher {
    private final MatrixClient matrixClient;
    private final RoomHistoryManager historyManager;
    private final Map<String, AtomicBoolean> runningOperations;
    private final String commandRoomId;
    private final String exportRoomId;
    private final TextSearchService textSearchService;
    private final AIService aiService;
    private final SemanticSearchService semanticSearchService;
    private final TimezoneService timezoneService;

    public CommandDispatcher(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
            String commandRoomId, String exportRoomId, RoomHistoryManager historyManager,
            Map<String, AtomicBoolean> runningOperations, TextSearchService textSearchService,
            AIService aiService, SemanticSearchService semanticSearchService, TimezoneService timezoneService) {
        this.matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        this.historyManager = historyManager;
        this.runningOperations = runningOperations;
        this.commandRoomId = commandRoomId;
        this.exportRoomId = exportRoomId;
        this.textSearchService = textSearchService;
        this.aiService = aiService;
        this.semanticSearchService = semanticSearchService;
        this.timezoneService = timezoneService;
    }

    /**
     * Dispatch a command based on its prefix
     */
    public boolean dispatchCommand(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        if ("!testcommand".equals(trimmed)) {
            matrixClient.sendText(responseRoomId, "Hello, world!");
            return true;
        } else if (trimmed.matches("!export\\d+h")) {
            handleExport(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.startsWith("!timezone")) {
            handleTimezone(trimmed, responseRoomId, sender);
            return true;
        } else if (trimmed.matches("!lastsummary(?:\\s+([A-Z]{3}))?(?:\\s+(.*))?")) {
            handleLastSummary(trimmed, roomId, sender, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!arliai(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?")) {
            handleArliAI(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed
                .matches(
                        "!arliai-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?")) {
            handleArliAITimestamp(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!cerebras(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?")) {
            handleCerebras(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed
                .matches(
                        "!cerebras-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?")) {
            handleCerebrasTimestamp(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!semantic(?:\\s+([A-Z]{3}))?\\s+(\\d+)h\\s+(.+)")) {
            handleSemanticSearch(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep(?:\\s+([A-Z]{3}))?\\s+(\\d+)([dh])\\s+(.+)")) {
            handleGrep(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep-slow(?:\\s+([A-Z]{3}))?\\s+(\\d+)([dh])\\s+(.+)")) {
            handleGrepSlow(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!search(?:\\s+([A-Z]{3}))?\\s+(\\d+)([dh])\\s+(.+)")) {
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

    private void handleExport(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        int hours = Integer.parseInt(trimmed.replaceAll("\\D+", ""));
        System.out.println("Received export command in " + roomId + " from " + sender + " (" + hours + "h)");
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String safeRoom = exportRoomId.replaceAll("[^A-Za-z0-9._-]", "_");
                String filename = safeRoom + "-last" + hours + "h-" + now + ".txt";

                matrixClient.sendMarkdown(responseRoomId,
                        "Starting export of last " + hours + "h from " + exportRoomId + " to " + filename);

                java.util.List<String> lines = historyManager.fetchRoomHistory(exportRoomId, hours, prevBatch);

                if (lines.isEmpty()) {
                    matrixClient.sendMarkdown(responseRoomId,
                            "No chat logs found for the last " + hours + "h to export from " + exportRoomId + ".");
                    return;
                }

                try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(filename))) {
                    for (String l : lines)
                        w.write(l + "\n");
                }

                matrixClient.sendMarkdown(responseRoomId,
                        "Export complete: " + filename + " (" + lines.size() + " messages)");
                System.out.println("Exported " + lines.size() + " messages to " + filename);
            } catch (Exception e) {
                System.out.println("Export failed: " + e.getMessage());
                try {
                    matrixClient.sendMarkdown(responseRoomId, "Export failed: " + e.getMessage());
                } catch (Exception ignore) {
                }
            }
        }).start();
    }

    private void handleLastSummary(String trimmed, String roomId, String sender, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!lastsummary(?:\\s+([A-Z]{3}))?(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            String question = matcher.group(2) != null ? matcher.group(2).trim() : null;

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            System.out.println("Received lastsummary command in " + roomId + " from " + sender);
            new Thread(() -> aiService.queryArliAIUnread(responseRoomId, exportRoomId, sender, zoneId, question))
                    .start();
        }
    }

    private void handleArliAI(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!arliai(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int hours = Integer.parseInt(matcher.group(2));
            String question = matcher.group(3) != null ? matcher.group(3).trim() : null;

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            System.out.println("Received arliai command in " + roomId + " from " + sender);
            new Thread(() -> aiService.queryArliAI(responseRoomId, exportRoomId, hours, prevBatch, question, -1,
                    zoneId)).start();
        }
    }

    private void handleArliAITimestamp(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern
                .compile(
                        "!arliai-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?")
                .matcher(trimmed);
        if (matcher.matches()) {
            String startDateStr = matcher.group(1);
            String timezoneAbbr = matcher.group(2);
            int durationHours = Integer.parseInt(matcher.group(3));
            String question = matcher.group(4) != null ? matcher.group(4).trim() : null;

            ZoneId userZone = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (userZone == null)
                return;

            long startTimestamp = java.time.LocalDateTime
                    .parse(startDateStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
                    .atZone(userZone)
                    .withZoneSameInstant(ZoneId.of("UTC"))
                    .toInstant()
                    .toEpochMilli();

            System.out.println("Received arli-ts command in " + roomId + " from " + sender);
            new Thread(() -> aiService.queryArliAI(responseRoomId, exportRoomId, durationHours, prevBatch, question,
                    startTimestamp, userZone)).start();
        }
    }

    private void handleCerebras(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!cerebras(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int hours = Integer.parseInt(matcher.group(2));
            String question = matcher.group(3) != null ? matcher.group(3).trim() : null;

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            System.out.println("Received cerebras command in " + roomId + " from " + sender);
            new Thread(() -> aiService.queryCerebras(responseRoomId, exportRoomId, hours, prevBatch, question, -1,
                    zoneId)).start();
        }
    }

    private void handleCerebrasTimestamp(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern
                .compile(
                        "!cerebras-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})(?:\\s+([A-Z]{3}))?\\s+(\\d+)h(?:\\s+(.*))?")
                .matcher(trimmed);
        if (matcher.matches()) {
            String startDateStr = matcher.group(1);
            String timezoneAbbr = matcher.group(2);
            int durationHours = Integer.parseInt(matcher.group(3));
            String question = matcher.group(4) != null ? matcher.group(4).trim() : null;

            ZoneId userZone = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (userZone == null)
                return;

            long startTimestamp = java.time.LocalDateTime
                    .parse(startDateStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
                    .atZone(userZone)
                    .withZoneSameInstant(ZoneId.of("UTC"))
                    .toInstant()
                    .toEpochMilli();

            System.out.println("Received cerebras-ts command in " + roomId + " from " + sender);
            new Thread(() -> aiService.queryCerebras(responseRoomId, exportRoomId, durationHours, prevBatch, question,
                    startTimestamp, userZone)).start();
        }
    }

    private void handleSemanticSearch(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!semantic(?:\\s+([A-Z]{3}))?\\s+(\\d+)h\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int hours = Integer.parseInt(matcher.group(2));
            String query = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            System.out.println("Received semantic search command in " + roomId + " from " + sender);
            new Thread(() -> semanticSearchService.performSemanticSearch(responseRoomId, exportRoomId, hours, prevBatch,
                    query, zoneId)).start();
        }
    }

    private void handleGrep(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!grep(?:\\s+([A-Z]{3}))?\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int duration = Integer.parseInt(matcher.group(2));
            String unit = matcher.group(3);
            String pattern = matcher.group(4).trim();

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            // Convert to hours
            int hours = unit.equals("d") ? duration * 24 : duration;

            System.out.println("Received grep command in " + roomId + " from " + sender);
            new Thread(() -> textSearchService.performGrep(roomId, sender, responseRoomId, exportRoomId, hours,
                    prevBatch, pattern,
                    zoneId)).start();
        }
    }

    private void handleGrepSlow(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!grep-slow(?:\\s+([A-Z]{3}))?\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int duration = Integer.parseInt(matcher.group(2));
            String unit = matcher.group(3);
            String pattern = matcher.group(4).trim();

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            // Convert to hours
            int hours = unit.equals("d") ? duration * 24 : duration;

            System.out.println("Received grep-slow command in " + roomId + " from " + sender);
            new Thread(() -> textSearchService.performGrepSlow(roomId, sender, responseRoomId, exportRoomId, hours,
                    prevBatch, pattern,
                    zoneId)).start();
        }
    }

    private void handleSearch(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!search(?:\\s+([A-Z]{3}))?\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            String timezoneAbbr = matcher.group(1);
            int duration = Integer.parseInt(matcher.group(2));
            String unit = matcher.group(3);
            String query = matcher.group(4).trim();

            ZoneId zoneId = resolveZoneId(sender, timezoneAbbr, responseRoomId);
            if (zoneId == null)
                return;

            // Convert to hours
            int hours = unit.equals("d") ? duration * 24 : duration;

            System.out.println("Received search command in " + roomId + " from " + sender);
            new Thread(() -> textSearchService.performSearch(roomId, sender, responseRoomId, exportRoomId, hours,
                    prevBatch, query,
                    zoneId)).start();
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

    private void handleTimezone(String trimmed, String responseRoomId, String sender) {
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            ZoneId current = timezoneService.getZoneIdForUser(sender);
            matrixClient.sendMarkdown(responseRoomId, "Your current timezone is: "
                    + (current != null ? current.getId() : "Not set") +
                    "\nUse `!timezone <Abbreviation or ZoneId>` to set it (e.g., `!timezone PST` or `!timezone America/Los_Angeles`).");
            return;
        }

        String tz = parts[1];
        ZoneId zoneId = timezoneService.getZoneIdFromAbbr(tz);
        if (zoneId == null) {
            matrixClient.sendMarkdown(responseRoomId, "Invalid timezone: " + tz
                    + ". Please use a common abbreviation like PST, EST, UTC or a full ZoneId like America/Los_Angeles.");
            return;
        }

        timezoneService.setZoneIdForUser(sender, zoneId);
        matrixClient.sendMarkdown(responseRoomId, "Your timezone has been set to: " + zoneId.getId());
    }

    private ZoneId resolveZoneId(String sender, String timezoneAbbr, String responseRoomId) {
        if (timezoneAbbr != null) {
            ZoneId zoneId = timezoneService.getZoneIdFromAbbr(timezoneAbbr);
            if (zoneId != null)
                return zoneId;
        }

        ZoneId saved = timezoneService.getZoneIdForUser(sender);
        if (saved != null)
            return saved;

        matrixClient.sendMarkdown(responseRoomId, "Timezone not specified and not set for your account. " +
                "Please specify it (e.g., `!arliai PST 24h`) or set it permanently with `!timezone <TZ>`.");
        return null;
    }

    private void handleHelp(String responseRoomId) {
        System.out.println("Received help command");
        String helpText = "**Matrix Bot Commands (Primary Use Case: !last)**\n\n" +
                "**!last** - Show your last message and read receipt status in export room (PRIMARY)\n\n" +
                "**Additional Commands:**\n\n" +
                "**!ping** - Measure and report ping latency\n\n" +
                "**!testcommand** - Test if the bot is responding\n\n" +
                "**!timezone <TZ>** - Set your preferred timezone (e.g. `!timezone PST`)\n\n" +
                "**!export<duration>h** - Export chat history (e.g., `!export24h`)\n\n" +
                "**!lastsummary [TZ] [question]** - Summarize all unread messages. TZ is optional if set via !timezone.\n\n"
                +
                "**!arliai, !cerebras [TZ] <hours>h [question]** - Query AI with chat logs\n\n" +
                "**!semantic [TZ] <hours>h <query>** - AI-free semantic search using local embeddings\n\n" +
                "**!grep, !grep-slow, !search [TZ] <hours>h <pattern>** - Pattern and term-based searches\n\n" +
                "**!abort** - Abort currently running operations";
        matrixClient.sendMarkdown(responseRoomId, helpText);
    }
}
