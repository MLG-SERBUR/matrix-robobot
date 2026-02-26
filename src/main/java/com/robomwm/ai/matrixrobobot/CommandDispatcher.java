package com.robomwm.ai.matrixrobobot;

import java.time.ZoneId;
import java.net.http.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.ArrayList;
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
        } else if (trimmed.matches("!longsummary(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!longsummary",
                    AIService.Backend.AUTO, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!summarylist(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!summarylist",
                    AIService.Backend.AUTO, AIService.Prompts.SUMMARYLIST_PREFIX);
            return true;
        } else if (trimmed.matches("!summary(?:\\s+.*)?")) {
            handleAICommand(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!summary",
                    AIService.Backend.AUTO, AIService.Prompts.SUMMARY_PREFIX);
            return true;
        } else if (trimmed.matches("!ask(?:\\s+.*)?")) {
            handleAsk(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
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
        } else if (trimmed.matches("!media\\s+(\\d+)([dh])\\s+(.+)")) {
            handleMedia(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if ("!abort".equals(trimmed)) {
            handleAbort(sender, responseRoomId);
            return true;
        } else if (trimmed.matches("!ttsexport\\s+(\\d+)(h)?")) {
            handleTTSExport(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
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
                    aiService.queryAIUnread(responseRoomId, exportRoomId, sender, zoneId, question,
                            AIService.Prompts.OVERVIEW_PREFIX, abortFlag, null);
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

    private void handleAsk(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
                           String exportRoomId) {
        String question = trimmed.replaceFirst("^!ask\\s*", "").trim();
        if (question.isEmpty()) question = null;

        System.out.println("Received !ask command in " + roomId + " from " + sender);

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final String fQuestion = question;

        new Thread(() -> {
            try {
                aiService.queryAsk(responseRoomId, exportRoomId, prevBatch, fQuestion, abortFlag);
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

    private void handleMedia(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!media\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            int duration = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            String query = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);
            if (zoneId == null)
                return;

            // Convert to hours
            int hours = unit.equals("d") ? duration * 24 : duration;

            System.out.println("Received media search command in " + roomId + " from " + sender);
            new Thread(() -> textSearchService.performMediaSearch(roomId, sender, responseRoomId, exportRoomId, hours,
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

    private void handleTTSExport(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!ttsexport\\s+(\\d+)(h)?").matcher(trimmed);
        if (matcher.matches()) {
            String value = matcher.group(1);
            boolean isDuration = matcher.group(2) != null;
            
            if (isDuration) {
                // Handle duration (hours)
                int hours = Integer.parseInt(value);
                
                System.out.println("Received TTS export command in " + roomId + " from " + sender + " (" + hours + "h)");
                
                new Thread(() -> {
                    try {
                        // Fetch messages with TTS-friendly formatting
                        // Use default timezone (UTC) when no timezone is specified for TTS export
                        ZoneId defaultZoneId = ZoneId.of("UTC");
                        RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(
                            exportRoomId, hours, prevBatch, -1, -1, defaultZoneId, -1);
                        
                        if (result.logs.isEmpty()) {
                            matrixClient.sendMarkdown(responseRoomId,
                                    "No chat logs found for the last " + hours + "h to export from " + exportRoomId + ".");
                            return;
                        }

                        // Apply TTS-friendly formatting
                        List<String> ttsLines = formatForTTS(result.logs);
                        
                        // Create filename with tts prefix and hours
                        long now = System.currentTimeMillis();
                        String safeRoom = exportRoomId.replaceAll("[^A-Za-z0-9._-]", "_");
                        String filename = safeRoom + "-tts-" + hours + "h-" + now + ".txt";

                        matrixClient.sendMarkdown(responseRoomId,
                                "Starting TTS-friendly export of last " + hours + "h from " + exportRoomId + " to " + filename);

                        // Write formatted content
                        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(filename))) {
                            for (String line : ttsLines)
                                w.write(line + "\n");
                        }

                        matrixClient.sendMarkdown(responseRoomId,
                                "TTS export complete: " + filename + " (" + ttsLines.size() + " formatted messages)");
                        System.out.println("TTS exported " + ttsLines.size() + " messages to " + filename);
                    } catch (Exception e) {
                        System.out.println("TTS export failed: " + e.getMessage());
                        try {
                            matrixClient.sendMarkdown(responseRoomId, "TTS export failed: " + e.getMessage());
                        } catch (Exception ignore) {
                        }
                    }
                }).start();
            } else {
                // Handle count (existing functionality)
                int messageCount = Integer.parseInt(value);
                
                System.out.println("Received TTS export command in " + roomId + " from " + sender + " (" + messageCount + " messages)");
                
                new Thread(() -> {
                    try {
                        // Fetch messages with TTS-friendly formatting
                        // Use default timezone (UTC) when no timezone is specified for TTS export
                        ZoneId defaultZoneId = ZoneId.of("UTC");
                        RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(
                            exportRoomId, -1, prevBatch, -1, -1, defaultZoneId, messageCount);
                        
                        if (result.logs.isEmpty()) {
                            matrixClient.sendMarkdown(responseRoomId,
                                    "No chat logs found for the last " + messageCount + " messages to export from " + exportRoomId + ".");
                            return;
                        }

                        // Apply TTS-friendly formatting
                        List<String> ttsLines = formatForTTS(result.logs);
                        
                        // Create filename with tts prefix
                        long now = System.currentTimeMillis();
                        String safeRoom = exportRoomId.replaceAll("[^A-Za-z0-9._-]", "_");
                        String filename = safeRoom + "-tts-" + messageCount + "msgs-" + now + ".txt";

                        matrixClient.sendMarkdown(responseRoomId,
                                "Starting TTS-friendly export of last " + messageCount + " messages from " + exportRoomId + " to " + filename);

                        // Write formatted content
                        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(filename))) {
                            for (String line : ttsLines)
                                w.write(line + "\n");
                        }

                        matrixClient.sendMarkdown(responseRoomId,
                                "TTS export complete: " + filename + " (" + ttsLines.size() + " formatted messages)");
                        System.out.println("TTS exported " + ttsLines.size() + " messages to " + filename);
                    } catch (Exception e) {
                        System.out.println("TTS export failed: " + e.getMessage());
                        try {
                            matrixClient.sendMarkdown(responseRoomId, "TTS export failed: " + e.getMessage());
                        } catch (Exception ignore) {
                        }
                    }
                }).start();
            }
        }
    }

    /**
     * Format chat logs for TTS consumption by removing timestamps, user homeserver,
     * angle brackets, and handling consecutive messages from same user.
     * First message from a user: "username: message"
     * Consecutive messages from same user: "message" (no username)
     */
    private List<String> formatForTTS(List<String> logs) {
        List<String> formatted = new ArrayList<>();
        String lastUser = null;

        for (String line : logs) {
            // Remove timestamp [yyyy-MM-dd HH:mm z]
            String noTimestamp = line.replaceAll("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} [A-Z]+\\]", "").trim();
            
            // Remove angle brackets and extract username and message
            // Format: <@username:server> message
            if (noTimestamp.matches("<[^>]+>.*")) {
                int firstSpace = noTimestamp.indexOf('>');
                if (firstSpace > 0) {
                    String userPart = noTimestamp.substring(1, firstSpace);
                    String message = noTimestamp.substring(firstSpace + 1).trim();
                    
                    // Extract username (remove @ symbol and homeserver part)
                    String username = userPart.split(":")[0];
                    // Remove the @ symbol if present
                    if (username.startsWith("@")) {
                        username = username.substring(1);
                    }
                    
                    // Handle consecutive messages from same user
                    if (lastUser == null || !lastUser.equals(username)) {
                        // First message from this user - include username
                        formatted.add(username + ": " + message);
                        lastUser = username;
                    } else {
                        // Consecutive message from same user - no username
                        formatted.add(message);
                    }
                }
            }
        }
        
        return formatted;
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
                "**!ttsexport <count>** - Export specified number of messages with TTS-friendly formatting (removes timestamps, homeserver, angle brackets, consolidates consecutive messages)\n" +
                "**!ttsexport <duration>h** - Export messages from the last specified hours with TTS-friendly formatting\n\n" +
                "**!lastsummary [question]** - Summarize all unread messages (uses saved TZ)\n\n" +
                "**!autolast** - Toggle automatic DM of last message when reading export room\n\n" +
                "**!autotldr** - Toggle automatic AI TLDR when reading export room (>100 msgs, >1h gap)\n\n" +
                "**!summary <link or count or duration> [question]** - Condensed summary with auto-fallback (ArliAI -> Cerebras)\n" +
                "**!summarylist <link or count or duration> [question]** - Bullet list of tech/VR/gaming/ethics/philosophy chats with auto-fallback\n" +
                "**!longsummary <link or count or duration> [question]** - Detailed overview with auto-fallback (ArliAI -> Cerebras)\n"
                +
                "**!arliai, !cerebras <link or count or duration> [question]** - Query specific AI backend (debug)\n"
                +
                "**!tldr <link or count or duration> [question]** - Quick 15s summary with auto-fallback\n"
                +
                "**!ask [question]** - Query AI backend with up to 16k tokens of history (no timestamps)\n"
                +
                "**!semantic <hours>h <query>** - AI-free semantic search using local embeddings\n\n" +
                "**!grep, !grep-slow, !search, !media <hours>h <pattern>** - Pattern and term-based searches (media searches for file attachments)\n\n" +
                "**!abort** - Abort currently running operations";
        matrixClient.sendMarkdown(responseRoomId, helpText);
    }
}
