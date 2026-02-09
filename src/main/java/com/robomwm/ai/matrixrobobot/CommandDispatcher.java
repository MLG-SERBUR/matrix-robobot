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
        } else if (trimmed.matches("!lastsummary(?:\\s+(.*))?")) {
            handleLastSummary(trimmed, roomId, sender, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!arliai(?:\\s+.*)?") || trimmed.matches("!arliai-ts(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!arliai",
                    AIService.Backend.ARLIAI, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!tldr(?:\\s+.*)?") || trimmed.matches("!tldr-ts(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!tldr",
                    AIService.Backend.AUTO, AIService.Prompts.TLDR_PREFIX);
            return true;
        } else if (trimmed.matches("!summary(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!summary",
                    AIService.Backend.AUTO, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!cerebras(?:\\s+.*)?") || trimmed.matches("!cerebras-ts(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!cerebras",
                    AIService.Backend.CEREBRAS, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!semantic\\s+(\\d+)h\\s+(.+)")) {
            handleSemanticSearch(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep\\s+(\\d+)([dh])\\s+(.+)")) {
            handleGrep(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep-slow\\s+(\\d+)([dh])\\s+(.+)")) {
            handleGrepSlow(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!search\\s+(\\d+)([dh])\\s+(.+)")) {
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
        Matcher matcher = Pattern.compile("!lastsummary(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String question = matcher.group(1) != null ? matcher.group(1).trim() : null;

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);
            if (zoneId == null)
                return;

            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            System.out.println("Received lastsummary command in " + roomId + " from " + sender);
            new Thread(() -> {
                try {
                    aiService.queryArliAIUnread(responseRoomId, exportRoomId, sender, zoneId, question,
                            AIService.Prompts.OVERVIEW_PREFIX, abortFlag);
                } finally {
                    runningOperations.remove(sender);
                }
            }).start();
        }
    }

    private void handleAICommand(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId, String commandName, AIService.Backend backend, String promptPrefix) {

        // Remove command name prefix (handle both !cmd and !cmd-ts for backward
        // compatibility in regex)
        String args = trimmed.replaceFirst("^" + commandName + "(?:-ts)?\\s*", "").trim();

        // Default values
        int hours = -1;
        int maxMessages = -1;
        long startTimestamp = -1;
        String question = null;

        ZoneId zoneId = resolveZoneId(sender, responseRoomId);
        if (zoneId == null)
            return;

        // Parse Args
        // Pattern 1: Matrix Link [Duration/Count] [Question]
        // Pattern 2: Duration/Count [Question]

        String[] parts = args.split("\\s+", 2);
        String firstArg = parts.length > 0 ? parts[0] : "";
        String remaining = parts.length > 1 ? parts[1] : "";

        String startEventId = null;
        boolean forward = false;

        // Check for Matrix link
        // Examples: 
        // https://matrix.to/#/!roomId:server/$eventId
        // https://matrix.to/#/!roomId:server/$eventId?via=server
        if (firstArg.contains("/$") || firstArg.contains("/e/")) {
            String eventId = null;
            if (firstArg.contains("/$")) {
                int start = firstArg.indexOf("/$") + 1;
                int end = firstArg.indexOf("?", start);
                if (end == -1) end = firstArg.length();
                eventId = firstArg.substring(start, end);
            } else if (firstArg.contains("/e/")) {
                int start = firstArg.indexOf("/e/") + 3;
                int end = firstArg.indexOf("/", start);
                if (end == -1) end = firstArg.length();
                eventId = firstArg.substring(start, end);
            }

            if (eventId != null && eventId.startsWith("$")) {
                startEventId = eventId;
                
                // Check if next arg is duration/limit
                String[] subParts = remaining.split("\\s+", 2);
                String possibleLimit = subParts.length > 0 ? subParts[0] : "";

                if (possibleLimit.matches("[+-]?\\d+(h)?")) {
                    if (possibleLimit.startsWith("+")) forward = true;
                    String cleanLimit = possibleLimit.replace("+", "").replace("-", "");
                    
                    if (cleanLimit.endsWith("h")) {
                        hours = Integer.parseInt(cleanLimit.replace("h", ""));
                    } else {
                        maxMessages = Integer.parseInt(cleanLimit);
                    }
                    question = subParts.length > 1 ? subParts[1].trim() : null;
                } else {
                    maxMessages = 100; // Default count
                    question = remaining.trim();
                }
            }
        } else if (firstArg.matches("[+-]?\\d+(h)?")) {
            // Count/Duration mode
            if (firstArg.startsWith("+")) forward = true;
            String cleanArg = firstArg.replace("+", "").replace("-", "");

            if (cleanArg.endsWith("h")) {
                hours = Integer.parseInt(cleanArg.replace("h", ""));
            } else {
                maxMessages = Integer.parseInt(cleanArg);
            }
            question = remaining.trim().isEmpty() ? null : remaining.trim();
        } else {
            // No valid first arg? Show usage.
            matrixClient.sendText(responseRoomId, "Usage: " + commandName + " <link|count|duration> [question]\n" +
                    "Example: !summary 1h what happened?\n" +
                    "Example: !summary 50 summarize this\n" +
                    "Example: !summary https://matrix.to/#/... 10 what is this about?");
            return;
        }

        System.out.println("Received " + commandName + " command in " + roomId + " from " + sender);

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final int fHours = hours;
        final int fMax = maxMessages;
        final String fEventId = startEventId;
        final boolean fForward = forward;
        final String fQuestion = question;

        new Thread(() -> {
            try {
                aiService.queryAI(responseRoomId, exportRoomId, fHours, prevBatch, fQuestion, fEventId, fForward,
                        zoneId, fMax, promptPrefix, abortFlag, backend);
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    private void handleSemanticSearch(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!semantic\\s+(\\d+)h\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            int hours = Integer.parseInt(matcher.group(1));
            String query = matcher.group(2).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);
            if (zoneId == null)
                return;

            System.out.println("Received semantic search command in " + roomId + " from " + sender);
            new Thread(() -> semanticSearchService.performSemanticSearch(responseRoomId, exportRoomId, hours, prevBatch,
                    query, zoneId)).start();
        }
    }

    private void handleGrep(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!grep\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            int duration = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            String pattern = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);
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
        Matcher matcher = Pattern.compile("!grep-slow\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            int duration = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            String pattern = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);
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
        Matcher matcher = Pattern.compile("!search\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            int duration = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            String query = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);
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
                    "\nUse `!timezone <Abbreviation or ZoneId>` (e.g., `!timezone PST`) " +
                    "or `!timezone <Your Local Time>` (e.g., `!timezone 1:14am` or `!timezone 14:30`) to set it.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length; i++)
            sb.append(parts[i]);
        String input = sb.toString();
        ZoneId zoneId = null;

        // Try parsing as time first
        if (input.contains(":") || input.matches(".*\\d+.*")) {
            zoneId = timezoneService.guessZoneIdFromTime(input);
        }

        // If not a time, try as abbreviation or ZoneId
        if (zoneId == null) {
            zoneId = timezoneService.getZoneIdFromAbbr(input);
        }

        if (zoneId == null) {
            matrixClient.sendMarkdown(responseRoomId, "Invalid timezone or time format: " + input
                    + ". Please use an abbreviation like PST, a full ZoneId like America/Los_Angeles, or your current local time like 1:14am or 14:30.");
            return;
        }

        timezoneService.setZoneIdForUser(sender, zoneId);
        matrixClient.sendMarkdown(responseRoomId, "Your timezone has been set to: " + zoneId.getId());
    }

    private ZoneId resolveZoneId(String sender, String responseRoomId) {
        ZoneId saved = timezoneService.getZoneIdForUser(sender);
        if (saved != null)
            return saved;

        matrixClient.sendMarkdown(responseRoomId, "Timezone not set for your account. " +
                "Please set it permanently with `!timezone <TZ>` or by providing your current time: `!timezone 1:14am` or `!timezone 14:30`.");
        return null;
    }

    private void handleHelp(String responseRoomId) {
        System.out.println("Received help command");
        String helpText = "**Matrix Bot Commands (Primary Use Case: !last)**\n\n" +
                "**!last** - Show your last message and read receipt status in export room (PRIMARY)\n\n" +
                "**Additional Commands:**\n\n" +
                "**!ping** - Measure and report ping latency\n\n" +
                "**!testcommand** - Test if the bot is responding\n\n" +
                "**!timezone <TZ or Time>** - Set your preferred timezone (e.g. `!timezone PST`, `!timezone 1:14am` or `!timezone 14:30`)\n\n"
                +
                "**!export<duration>h** - Export chat history (e.g., `!export24h`)\n\n" +
                "**!lastsummary [question]** - Summarize all unread messages (uses saved TZ)\n\n" +
                "**!summary <link or count or duration> [question]** - Detailed summary with auto-fallback (ArliAI -> Cerebras)\n"
                +
                "**!arliai, !cerebras <link or count or duration> [question]** - Query specific AI backend (debug)\n"
                +
                "**!tldr <link or count or duration> [question]** - Quick 15s summary with auto-fallback\n"
                +
                "**!semantic <hours>h <query>** - AI-free semantic search using local embeddings\n\n" +
                "**!grep, !grep-slow, !search <hours>h <pattern>** - Pattern and term-based searches\n\n" +
                "**!abort** - Abort currently running operations";
        matrixClient.sendMarkdown(responseRoomId, helpText);
    }
}
