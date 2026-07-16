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
 * Routes and executes commands (export, arliai, semantic, grep,
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
    private final VisionAIService visionAIService;
    private final DebugAIService debugAIService;
    private final SemanticSearchService semanticSearchService;
    private final TimezoneService timezoneService;
    private final AiSearchService aiSearchService;
    private final MatrixSearchService matrixSearchService;

    /**
     * Parsed command arguments for history-based commands.
     * Contains hours, maxMessages, startEventId, forward flag, and remaining question/text.
     */
    private static class ParsedHistoryArgs {
        public final int hours;
        public final int maxMessages;
        public final String startEventId;
        public final boolean forward;
        public final String remaining;

        public ParsedHistoryArgs(int hours, int maxMessages, String startEventId, boolean forward, String remaining) {
            this.hours = hours;
            this.maxMessages = maxMessages;
            this.startEventId = startEventId;
            this.forward = forward;
            this.remaining = remaining;
        }
    }

    /**
     * Parse history command arguments (count, duration, message link with +/- count).
     * This is shared between !export and AI commands like !summary, !overview, etc.
     *
     * @param commandName The command name to strip (e.g., "!export", "!summary")
     * @param trimmed The full command string
     * @param defaultToTokenLimit If true, defaults to token limit mode when no valid args found
     * @return ParsedHistoryArgs containing the parsed parameters
     */
    private ParsedHistoryArgs parseHistoryCommandArgs(String commandName, String trimmed, boolean defaultToTokenLimit) {
        // Remove command name prefix (handle both !cmd and !cmd-ts for backward compatibility)
        String args = trimmed.replaceFirst("^" + commandName + "(?:-ts)?\\s*", "").trim();

        // Default values
        int hours = -1;
        int maxMessages = -1;
        String startEventId = null;
        boolean forward = false;
        String remaining = "";

        if (args.isEmpty()) {
            return new ParsedHistoryArgs(hours, maxMessages, startEventId, forward, remaining);
        }

        // Parse Args
        // Pattern 1: Matrix Link [Duration/Count] [Question]
        // Pattern 2: Duration/Count [Question]

        String[] parts = args.split("\\s+", 2);
        String firstArg = parts.length > 0 ? parts[0] : "";
        remaining = parts.length > 1 ? parts[1] : "";

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
                    remaining = subParts.length > 1 ? subParts[1].trim() : "";
                } else if (!possibleLimit.isEmpty()) {
                    // If there's a non-empty argument that doesn't match the pattern, default to count
                    maxMessages = 100;
                    remaining = remaining.trim();
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
            remaining = remaining.trim();
        } else if (defaultToTokenLimit) {
            // For commands like !ask, treat the whole args as the question
            remaining = args;
        }

        return new ParsedHistoryArgs(hours, maxMessages, startEventId, forward, remaining);
    }

    private static final List<String> ARLIAI_MODELS = List.of(
            "Qwen3.5-27B-Anko",
            "Qwen3.5-27B-BlueStar-Derestricted",
            "Qwen3.5-27B-BlueStar-Derestricted-Lite",
            "Qwen3.5-27B-BlueStar-v2-Derestricted",
            "Qwen3.5-27B-BlueStar-v2-Derestricted-Lite",
            "Qwen3.5-27B-Musica-v1",
            "Qwen3.5-27B-Omega-Evolution-v2.0-Derestricted",
            "Qwen3.5-27B-Omega-Evolution-v2.0-Derestricted-Lite",
            "Qwen3.5-27B-Vivid-Durian",
            "Qwen3.5-27B-Writer-Derestricted",
            "Qwen3.5-27B-Writer-Derestricted-Lite",
            "Qwen3.5-27B-Writer-V2-Derestricted",
            "Qwen3.5-27B-Writer-V2-Derestricted-Lite",
            "Qwen3.5-27B-Derestricted"
    );

    public CommandDispatcher(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
            String commandRoomId, String exportRoomId, RoomHistoryManager historyManager,
            Map<String, AtomicBoolean> runningOperations, TextSearchService textSearchService,
            AIService aiService, VisionAIService visionAIService,
            SemanticSearchService semanticSearchService,
            TimezoneService timezoneService, String arliApiKey) {
        this.matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        this.historyManager = historyManager;
        this.runningOperations = runningOperations;
        this.commandRoomId = commandRoomId;
        this.exportRoomId = exportRoomId;
        this.textSearchService = textSearchService;
        this.aiService = aiService;
        this.visionAIService = visionAIService;
        this.debugAIService = new DebugAIService(client, mapper, homeserver, accessToken, arliApiKey);
        this.semanticSearchService = semanticSearchService;
        this.timezoneService = timezoneService;
        this.aiSearchService = new AiSearchService(client, mapper, homeserver, accessToken, arliApiKey);
        this.matrixSearchService = new MatrixSearchService(matrixClient, client, mapper, homeserver, accessToken, runningOperations);
    }

    /**
     * Dispatch a command based on its prefix
     */
    public boolean dispatchCommand(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        if ("!testcommand".equals(trimmed)) {
            matrixClient.sendText(responseRoomId, "Hello, world!");
            return true;
        } else if (trimmed.startsWith("!export ") || trimmed.matches("!export\\d+h") || trimmed.matches("!export\\d+")) {
            handleExport(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.startsWith("!timezone")) {
            handleTimezone(trimmed, responseRoomId, sender);
            return true;
        } else if (trimmed.matches("!lastsummary(?:\\s+(.*))?")) {
            handleLastSummary(trimmed, roomId, sender, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!qtldr(?:\\s+.*)?") || trimmed.matches("!qtldr-ts(?:\\s+.*)?")) {
            handleHistoryAICommandFiltered(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!qtldr",
                    AIService.Backend.AUTO, AIService.Prompts.TLDR_PREFIX);
            return true;
        } else if (trimmed.matches("!qoverview(?:\\s+.*)?")) {
            handleHistoryAICommandFiltered(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!qoverview",
                    AIService.Backend.AUTO, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!qsummary(?:\\s+.*)?")) {
            handleHistoryAICommandFiltered(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!qsummary",
                    AIService.Backend.AUTO, AIService.Prompts.SUMMARY_PREFIX);
            return true;
        } else if (trimmed.matches("!qask(?:\\s+.*)?")) {
            handleAskFiltered(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!debugai(?:\\s+.*)?") || trimmed.matches("!debugai-ts(?:\\s+.*)?")) {
            handleHistoryAICommand(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!debugai",
                    AIService.Backend.AUTO, AIService.Prompts.DEBUGAI_PREFIX);
            return true;
        } else if (trimmed.matches("!tldr(?:\\s+.*)?") || trimmed.matches("!tldr-ts(?:\\s+.*)?")) {
            handleHistoryAICommand(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!tldr",
                    AIService.Backend.AUTO, AIService.Prompts.TLDR_PREFIX);
            return true;
        } else if (trimmed.matches("!overview(?:\\s+.*)?")) {
            handleHistoryAICommand(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!overview",
                    AIService.Backend.AUTO, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!ioverview(?:\\s+.*)?")) {
            handleHistoryAICommand(visionAIService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!ioverview",
                    AIService.Backend.AUTO, AIService.Prompts.OVERVIEW_PREFIX);
            return true;
        } else if (trimmed.matches("!summary(?:\\s+.*)?")) {
            handleHistoryAICommand(aiService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!summary",
                    AIService.Backend.AUTO, AIService.Prompts.SUMMARY_PREFIX);
            return true;
        } else if (trimmed.matches("!isummary(?:\\s+.*)?")) {
            handleHistoryAICommand(visionAIService, trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, "!isummary",
                    AIService.Backend.AUTO, AIService.Prompts.SUMMARY_PREFIX);
            return true;
        } else if (trimmed.matches("!ask(?:\\s+.*)?")) {
            handleAsk(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId, null, AIService.AI_TIMEOUT_SECONDS, AIService.Backend.AUTO);
            return true;
        } else if (trimmed.matches("!debugarliai(?:\\s+.*)?")) {
            handleDebugArliai(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!arliai(?:\\s+.*)?")) {
            handleArliai(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!aisearch\\s+(\\d+)([dh])\\s+(.+)")) {
            handleAiSearch(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!semantic\\s+(\\d+)h\\s+(.+)")) {
            handleSemanticSearch(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!grep\\s+(\\d+)([dh])\\s+(.+)")) {
            return handleTextSearchCommand(trimmed, "!grep\\s+(\\d+)([dh])\\s+(.+)", "grep", roomId, sender, prevBatch, responseRoomId, exportRoomId, textSearchService::performGrep);
        } else if (trimmed.matches("!searchtext\\s*")) {
            matrixClient.sendText(responseRoomId, "Usage: !searchtext <hours>h <pattern>\nSearches message text for the given pattern.");
            return true;
        } else if (trimmed.matches("!searchtext\\s+(\\d+)([dh])\\s+(.+)")) {
            return handleTextSearchCommand(trimmed, "!searchtext\\s+(\\d+)([dh])\\s+(.+)", "searchtext", roomId, sender, prevBatch, responseRoomId, exportRoomId, textSearchService::performSearch);
        } else if (trimmed.matches("!search\\s+(.+)")) {
            handleMatrixSearch(trimmed, roomId, sender, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!page\\s+(\\d+)")) {
            handlePage(trimmed, sender, responseRoomId);
            return true;
        } else if (trimmed.matches("!media\\s+(\\d+)([dh])\\s+(.+)")) {
            return handleTextSearchCommand(trimmed, "!media\\s+(\\d+)([dh])\\s+(.+)", "media search", roomId, sender, prevBatch, responseRoomId, exportRoomId, textSearchService::performMediaSearch);
        } else if ("!abort".equals(trimmed)) {
            handleAbort(sender, responseRoomId);
            return true;
        } else if (trimmed.matches("!ttsexport\\s+(\\d+)(h)?")) {
            handleTTSExport(trimmed, roomId, sender, prevBatch, responseRoomId, exportRoomId);
            return true;
        } else if (trimmed.matches("!help\\s*") || trimmed.matches("!help\\s+(\\d+)")) {
            int page = 1;
            Matcher helpMatcher = Pattern.compile("!help\\s+(\\d+)").matcher(trimmed);
            if (helpMatcher.matches()) {
                page = Integer.parseInt(helpMatcher.group(1));
            }
            handleHelp(responseRoomId, page);
            return true;
        }
        return false;
    }

    private void handleExport(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
            String exportRoomId) {
        // Use shared parser
        ParsedHistoryArgs parsed = parseHistoryCommandArgs("!export", trimmed, false);

        // If we have a startEventId but no count or hours, use default
        int hours = parsed.hours;
        int maxMessages = parsed.maxMessages;
        String startEventId = parsed.startEventId;
        boolean forward = parsed.forward;

        if (startEventId != null && hours == -1 && maxMessages == -1) {
            maxMessages = 100;
        }

        // If no count or hours specified and no link, show usage
        if (hours == -1 && maxMessages == -1 && startEventId == null) {
            matrixClient.sendMarkdown(responseRoomId, "Please specify a count, duration, or link. Usage: `!export <count>`, `!export <duration>h`, or `!export <link> [+|-<count>]`");
            return;
        }

        System.out.println("Received export command in " + roomId + " from " + sender + 
                " (hours=" + hours + ", maxMessages=" + maxMessages + ", startEventId=" + startEventId + ", forward=" + forward + ")");

        ZoneId zoneId = resolveZoneId(sender, responseRoomId);

        // Make copies for lambda
        final int fHours = hours;
        final int fMaxMessages = maxMessages;
        final String fStartEventId = startEventId;
        final boolean fForward = forward;
        final String fExportRoomId = exportRoomId;
        final String fPrevBatch = prevBatch;

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String safeRoom = fExportRoomId.replaceAll("[^A-Za-z0-9._-]", "_");
                String filename;
                String description;

                if (fStartEventId != null) {
                    String direction = fForward ? "after" : "before";
                    if (fHours > 0) {
                        filename = safeRoom + "-" + fStartEventId + "-" + direction + fHours + "h-" + now + ".txt";
                        description = "Starting export of " + fHours + "h " + direction + " " + fStartEventId + " from " + fExportRoomId + " to " + filename;
                    } else {
                        filename = safeRoom + "-" + fStartEventId + "-" + direction + fMaxMessages + "msgs-" + now + ".txt";
                        description = "Starting export of " + fMaxMessages + " messages " + direction + " " + fStartEventId + " from " + fExportRoomId + " to " + filename;
                    }
                } else if (fHours > 0) {
                    filename = safeRoom + "-last" + fHours + "h-" + now + ".txt";
                    description = "Starting export of last " + fHours + "h from " + fExportRoomId + " to " + filename;
                } else {
                    filename = safeRoom + "-last" + fMaxMessages + "msgs-" + now + ".txt";
                    description = "Starting export of last " + fMaxMessages + " messages from " + fExportRoomId + " to " + filename;
                }

                matrixClient.sendMarkdown(responseRoomId, description);

                java.util.List<String> lines;

                if (fStartEventId != null) {
                    // Use relative fetching for message links
                    RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryRelative(
                            fExportRoomId, fHours, fPrevBatch, fStartEventId, fForward, zoneId, fMaxMessages, abortFlag);
                    lines = result.logs;
                } else {
                    // Use detailed fetching for count or duration
                    RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(
                            fExportRoomId, fHours, fPrevBatch, -1, -1, zoneId, fMaxMessages, abortFlag);
                    lines = result.logs;
                }

                if (lines.isEmpty()) {
                    if (abortFlag.get()) {
                        System.out.println("Export aborted by user.");
                        return;
                    }
                    String notFoundMessage = "No chat logs found";
                    if (fStartEventId != null) {
                        String direction = fForward ? "after" : "before";
                        if (fHours > 0) {
                            notFoundMessage += " for " + fHours + "h " + direction + " " + fStartEventId;
                        } else {
                            notFoundMessage += " for " + fMaxMessages + " messages " + direction + " " + fStartEventId;
                        }
                    } else if (fHours > 0) {
                        notFoundMessage += " for the last " + fHours + "h";
                    } else {
                        notFoundMessage += " for the last " + fMaxMessages + " messages";
                    }
                    notFoundMessage += " to export from " + fExportRoomId + ".";
                    matrixClient.sendMarkdown(responseRoomId, notFoundMessage);
                    return;
                }

                if (abortFlag.get()) return;

                try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(filename))) {
                    for (String l : lines) {
                        if (abortFlag.get()) {
                            System.out.println("Export aborted during file write.");
                            return;
                        }
                        w.write(l + "\n");
                    }
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
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    private void handleLastSummary(String trimmed, String roomId, String sender, String responseRoomId,
            String exportRoomId) {
        Matcher matcher = Pattern.compile("!lastsummary(?:\\s+(.*))?").matcher(trimmed);
        if (matcher.matches()) {
            String question = matcher.group(1) != null ? matcher.group(1).trim() : null;

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);

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

    private void handleHistoryAICommand(AIService service, String trimmed, String roomId, String sender,
            String prevBatch, String responseRoomId,
            String exportRoomId, String commandName, AIService.Backend backend, String promptPrefix) {

        ZoneId zoneId = resolveZoneId(sender, responseRoomId);
        
        // Use shared parser
        ParsedHistoryArgs parsed = parseHistoryCommandArgs(commandName, trimmed, true);

        if (parsed.startEventId == null && parsed.hours == -1 && parsed.maxMessages == -1) {
            // No valid history args found, default to token limit (like !ask)
            String questionArg = parsed.remaining.isEmpty() ? null : parsed.remaining;
            System.out.println("Received " + commandName + " command in " + roomId + " from " + sender + " (defaulting to token limit)");
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            new Thread(() -> {
                try {
                    service.queryAsk(responseRoomId, exportRoomId, null, questionArg, promptPrefix, abortFlag, null, AIService.AI_TIMEOUT_SECONDS, backend, zoneId);
                } finally {
                    runningOperations.remove(sender);
                }
            }).start();
            return;
        }

        System.out.println("Received " + commandName + " command in " + roomId + " from " + sender);

        // If we have a link but no count/hours, use default count
        int hours = parsed.hours;
        int maxMessages = parsed.maxMessages;
        if (parsed.startEventId != null && hours == -1 && maxMessages == -1) {
            maxMessages = 100;
        }

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final int fHours = hours;
        final int fMax = maxMessages;
        final String fEventId = parsed.startEventId;
        final boolean fForward = parsed.forward;
        final String fQuestion = parsed.remaining.isEmpty() ? null : parsed.remaining;

        new Thread(() -> {
            try {
                service.queryAI(responseRoomId, exportRoomId, fHours, null, fQuestion, fEventId, fForward,
                        zoneId, fMax, promptPrefix, abortFlag, backend);
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    private void handleHistoryAICommandFiltered(AIService service, String trimmed, String roomId, String sender,
            String prevBatch, String responseRoomId,
            String exportRoomId, String commandName, AIService.Backend backend, String promptPrefix) {

        ZoneId zoneId = resolveZoneId(sender, responseRoomId);
        
        ParsedHistoryArgs parsed = parseHistoryCommandArgs(commandName, trimmed, true);

        if (parsed.startEventId == null && parsed.hours == -1 && parsed.maxMessages == -1) {
            String questionArg = parsed.remaining.isEmpty() ? null : parsed.remaining;
            System.out.println("Received " + commandName + " command in " + roomId + " from " + sender + " (defaulting to token limit, quality filtered)");
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            new Thread(() -> {
                try {
                    service.queryAskFiltered(responseRoomId, exportRoomId, null, questionArg, promptPrefix, abortFlag, null, AIService.AI_TIMEOUT_SECONDS, backend, zoneId);
                } finally {
                    runningOperations.remove(sender);
                }
            }).start();
            return;
        }

        System.out.println("Received " + commandName + " command in " + roomId + " from " + sender + " (quality filtered)");

        int hours = parsed.hours;
        int maxMessages = parsed.maxMessages;
        if (parsed.startEventId != null && hours == -1 && maxMessages == -1) {
            maxMessages = 100;
        }

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final int fHours = hours;
        final int fMax = maxMessages;
        final String fEventId = parsed.startEventId;
        final boolean fForward = parsed.forward;
        final String fQuestion = parsed.remaining.isEmpty() ? null : parsed.remaining;

        new Thread(() -> {
            try {
                service.queryAIFiltered(responseRoomId, exportRoomId, fHours, null, fQuestion, fEventId, fForward,
                        zoneId, fMax, promptPrefix, abortFlag, backend);
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    private void handleAsk(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
                            String exportRoomId, String forcedModel, int timeoutSeconds, AIService.Backend preferredBackend) {
        String question = trimmed.replaceFirst("^!ask\\s*", "").trim();
        if (question.isEmpty()) question = null;

        System.out.println("Received !ask command in " + roomId + " from " + sender);

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final String fQuestion = question;
        ZoneId zoneId = resolveZoneId(sender, responseRoomId);

        new Thread(() -> {
            try {
                aiService.queryAsk(responseRoomId, exportRoomId, null, fQuestion, AIService.Prompts.ASK_PREFIX, abortFlag, forcedModel, timeoutSeconds, preferredBackend, zoneId);
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    private void handleAskFiltered(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
                            String exportRoomId) {
        String question = trimmed.replaceFirst("^!qask\\s*", "").trim();
        if (question.isEmpty()) question = null;

        System.out.println("Received !qask command in " + roomId + " from " + sender + " (quality filtered)");

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final String fQuestion = question;
        ZoneId zoneId = resolveZoneId(sender, responseRoomId);

        new Thread(() -> {
            try {
                aiService.queryAskFiltered(responseRoomId, exportRoomId, null, fQuestion, AIService.Prompts.ASK_PREFIX, abortFlag, null, AIService.AI_TIMEOUT_SECONDS, AIService.Backend.AUTO, zoneId);
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    /**
     * Fuzzy match a model name input to the list of available models.
     * Uses case-insensitive contains matching.
     */
    private String fuzzyMatchModel(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        
        String lowerInput = input.toLowerCase();
        
        // First, try exact match (case-insensitive)
        for (String model : ARLIAI_MODELS) {
            if (model.equalsIgnoreCase(input)) {
                return model;
            }
        }
        
        // Then try contains match
        for (String model : ARLIAI_MODELS) {
            if (model.toLowerCase().contains(lowerInput)) {
                return model;
            }
        }
        
        // Try matching with underscores replaced by hyphens
        String normalizedInput = lowerInput.replace("_", "-");
        for (String model : ARLIAI_MODELS) {
            if (model.toLowerCase().replace("_", "-").contains(normalizedInput)) {
                return model;
            }
        }
        
        return null;
    }

    private void handleArliai(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
                              String exportRoomId) {
        // Format: !arliai <model> <prompt>
        String args = trimmed.replaceFirst("^!arliai\\s*", "").trim();
        
        if (args.isEmpty()) {
            matrixClient.sendText(responseRoomId, "Usage: !arliai <model> <prompt>\n" +
                    "Available models: " + String.join(", ", ARLIAI_MODELS) + "\n" +
                    "Model names are fuzzy matched (e.g., 'musica', 'vivid', 'writer', 'bluestar')");
            return;
        }
        
        String[] parts = args.split("\\s+", 2);
        String modelInput = parts[0];
        String question = parts.length > 1 ? parts[1].trim() : null;
        
        String matchedModel = fuzzyMatchModel(modelInput);
        
        if (matchedModel == null) {
            matrixClient.sendText(responseRoomId, "Unknown model: " + modelInput + "\n" +
                    "Available models: " + String.join(", ", ARLIAI_MODELS) + "\n" +
                    "Model names are fuzzy matched (e.g., 'musica', 'vivid', 'writer', 'bluestar')");
            return;
        }
        
        if (question == null || question.isEmpty()) {
            matrixClient.sendText(responseRoomId, "Please provide a prompt after the model name.\n" +
                    "Usage: !arliai " + modelInput + " <your prompt here>");
            return;
        }

        System.out.println("Received !arliai command in " + roomId + " from " + sender + " (model: " + matchedModel + ")");

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final String fQuestion = question;
        final String fModel = matchedModel;
        ZoneId zoneId = resolveZoneId(sender, responseRoomId);

        new Thread(() -> {
            try {
                aiService.queryAsk(responseRoomId, exportRoomId, null, fQuestion, AIService.Prompts.ASK_PREFIX, abortFlag, fModel, AIService.AI_TIMEOUT_SECONDS, AIService.Backend.ARLIAI, zoneId);
            } finally {
                runningOperations.remove(sender);
            }
        }).start();
    }

    private void handleDebugArliai(String trimmed, String roomId, String sender, String prevBatch, String responseRoomId,
                                   String exportRoomId) {
        // Format: !debugarliai <model> [param=value ...] <prompt>
        String args = trimmed.replaceFirst("^!debugarliai\\s*", "").trim();
        
        if (args.isEmpty()) {
            matrixClient.sendMarkdown(responseRoomId, DebugAIService.getHelpText());
            return;
        }
        
        DebugAIService.ParseResult result = DebugAIService.parseArguments(args);
        
        if (result.hasError()) {
            matrixClient.sendText(responseRoomId, "Error: " + result.error + "\n\n" + DebugAIService.getHelpText());
            return;
        }

        System.out.println("Received !debugarliai command in " + roomId + " from " + sender + " (model: " + result.config.model + ")");

        AtomicBoolean abortFlag = new AtomicBoolean(false);
        runningOperations.put(sender, abortFlag);

        final DebugAIService.DebugConfig fConfig = result.config;
        final String fPrompt = result.prompt;

        new Thread(() -> {
            try {
                debugAIService.queryDebugAI(responseRoomId, exportRoomId, null, fConfig, fPrompt, abortFlag, historyManager);
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

            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            System.out.println("Received semantic search command in " + roomId + " from " + sender);
            new Thread(() -> {
                try {
                    semanticSearchService.performSemanticSearch(responseRoomId, exportRoomId, hours, null,
                        query, zoneId, abortFlag);
                } finally {
                    runningOperations.remove(sender);
                }
            }).start();
        }
    }

    private void handleAiSearch(String trimmed, String roomId, String sender, String prevBatch,
            String responseRoomId, String exportRoomId) {
        Matcher matcher = Pattern.compile("!aisearch\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
        if (matcher.matches()) {
            int duration = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            String query = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);

            int hours = unit.equals("d") ? duration * 24 : duration;

            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            System.out.println("Received aisearch command in " + roomId + " from " + sender);
            new Thread(() -> {
                try {
                    aiSearchService.performAiSearch(responseRoomId, exportRoomId, hours, null,
                        query, zoneId, abortFlag);
                } finally {
                    runningOperations.remove(sender);
                }
            }).start();
        }
    }

    private void handleMatrixSearch(String trimmed, String roomId, String sender, String responseRoomId,
            String searchRoomId) {
        // Extract optional user:@mxid or u:@mxid parameter from anywhere in the arguments
        java.util.List<String> filterSenders = null;
        String remaining = trimmed.substring("!search".length()).trim();
        java.util.regex.Matcher userMatcher = Pattern.compile("(?i)(?:user|u):(\\S+)").matcher(remaining);
        if (userMatcher.find()) {
            String filterSender = userMatcher.group(1);
            remaining = remaining.substring(0, userMatcher.start()) + remaining.substring(userMatcher.end());
            remaining = remaining.trim().replaceAll("\\s{2,}", " ");
            filterSenders = resolveSearchSenders(roomId, filterSender);
            if (filterSenders.isEmpty()) {
                matrixClient.sendNotice(responseRoomId,
                        "No matching room member(s) found for user parameter: " + filterSender);
                return;
            }
        }

        if (remaining.isEmpty()) {
            matrixClient.sendText(responseRoomId,
                    "Usage: !search [<hours>h|<days>d] [user:<username>|u:<username>] <query>\n" +
                    "Example: !search 24h u:alice:example.com hello\n" +
                    "Example: !search 24h u:alice hello");
            return;
        }

        Matcher matcher = Pattern.compile("(?:(\\d+)([dh])\\s+)?(.+)").matcher(remaining);
        if (matcher.matches()) {
            int hours = -1;
            if (matcher.group(1) != null && matcher.group(2) != null) {
                int duration = Integer.parseInt(matcher.group(1));
                String unit = matcher.group(2);
                hours = unit.equals("d") ? duration * 24 : duration;
            }
            String query = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);

            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            System.out.println("Received Matrix search command in " + roomId + " from " + sender
                    + (filterSenders != null ? " (filtering by user(s): " + String.join(", ", filterSenders) + ")" : ""));
            final int searchHours = hours;
            final java.util.List<String> searchFilterSenders = filterSenders;
            new Thread(() -> {
                try {
                    matrixSearchService.performMatrixSearch(roomId, sender, responseRoomId, searchRoomId, query,
                            searchFilterSenders, searchHours, zoneId, abortFlag);
                } finally {
                    runningOperations.remove(sender);
                }
            }).start();
        }
    }

    private java.util.List<String> resolveSearchSenders(String roomId, String userParameter) {
        if (userParameter == null || userParameter.isBlank()) {
            return List.of();
        }

        String normalized = userParameter.startsWith("@") ? userParameter : "@" + userParameter;
        if (normalized.indexOf(':', 1) > 0) {
            return List.of(normalized);
        }

        String localpart = normalized.substring(1);
        java.util.List<String> memberIds = matrixClient.getRoomMemberIds(roomId);
        java.util.List<String> matches = new ArrayList<>();
        for (String memberId : memberIds) {
            if (memberId == null || !memberId.startsWith("@") || memberId.indexOf(':', 1) < 0) {
                continue;
            }
            String memberLocalpart = memberId.substring(1, memberId.indexOf(':', 1));
            if (memberLocalpart.equalsIgnoreCase(localpart)) {
                matches.add(memberId);
            }
        }
        return matches;
    }

    private void handlePage(String trimmed, String sender, String responseRoomId) {
        Matcher matcher = Pattern.compile("!page\\s+(\\d+)").matcher(trimmed);
        if (matcher.matches()) {
            int pageNum = Integer.parseInt(matcher.group(1));
            System.out.println("Received !page " + pageNum + " command from " + sender);
            if (matrixSearchService.goToPage(sender, pageNum)) return;
            if (textSearchService.goToPage(sender, pageNum)) return;
            matrixClient.sendNotice(responseRoomId,
                    "Invalid page number or no active search. Use !search <query> or !searchtext <hours>h <pattern> to start a search.");
        }
    }

    @FunctionalInterface
    public interface TextSearchAction {
        void execute(String roomId, String sender, String responseRoomId, String exportRoomId, int hours, String prevBatch, String pattern, ZoneId zoneId);
    }

    private boolean handleTextSearchCommand(String trimmed, String regex, String commandName, String roomId, String sender, String prevBatch, String responseRoomId, String exportRoomId, TextSearchAction action) {
        Matcher matcher = Pattern.compile(regex).matcher(trimmed);
        if (matcher.matches()) {
            int duration = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            String input = matcher.group(3).trim();

            ZoneId zoneId = resolveZoneId(sender, responseRoomId);

            int hours = unit.equals("d") ? duration * 24 : duration;

            System.out.println("Received " + commandName + " command in " + roomId + " from " + sender);
            new Thread(() -> action.execute(roomId, sender, responseRoomId, exportRoomId, hours, null, input, zoneId)).start();
            return true;
        }
        return false;
    }

    private void handleAbort(String sender, String responseRoomId) {
        System.out.println("Received abort command from " + sender);
        AtomicBoolean abortFlag = runningOperations.get(sender);
        if (abortFlag != null) {
            abortFlag.set(true);
            matrixClient.sendText(responseRoomId, "Aborting your running operations (summaries, searches, etc.)...");
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

        matrixClient.sendNotice(responseRoomId, "Timezone not set. Using UTC by default. " +
                "Set it with !timezone <TZ> or your local time: !timezone 1:14am or !timezone 14:30");
        return ZoneId.of("UTC");
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
                
                AtomicBoolean abortFlag = new AtomicBoolean(false);
                runningOperations.put(sender, abortFlag);

                new Thread(() -> {
                    try {
                        // Fetch messages with TTS-friendly formatting
                        // Use default timezone (UTC) when no timezone is specified for TTS export
                        ZoneId defaultZoneId = ZoneId.of("UTC");
                        RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(
                            exportRoomId, hours, prevBatch, -1, -1, defaultZoneId, -1, abortFlag);
                        
                        if (result.logs.isEmpty()) {
                            if (abortFlag.get()) {
                                System.out.println("TTS export aborted by user.");
                                return;
                            }
                            matrixClient.sendMarkdown(responseRoomId,
                                    "No chat logs found for the last " + hours + "h to export from " + exportRoomId + ".");
                            return;
                        }

                        if (abortFlag.get()) return;

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
                            for (String line : ttsLines) {
                                if (abortFlag.get()) {
                                    System.out.println("TTS export aborted during file write.");
                                    return;
                                }
                                w.write(line + "\n");
                            }
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
                    } finally {
                        runningOperations.remove(sender);
                    }
                }).start();
            } else {
                // Handle count (existing functionality)
                int messageCount = Integer.parseInt(value);
                
                System.out.println("Received TTS export command in " + roomId + " from " + sender + " (" + messageCount + " messages)");
                
                AtomicBoolean abortFlag = new AtomicBoolean(false);
                runningOperations.put(sender, abortFlag);

                new Thread(() -> {
                    try {
                        // Fetch messages with TTS-friendly formatting
                        // Use default timezone (UTC) when no timezone is specified for TTS export
                        ZoneId defaultZoneId = ZoneId.of("UTC");
                        RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(
                            exportRoomId, -1, prevBatch, -1, -1, defaultZoneId, messageCount, abortFlag);
                        
                        if (result.logs.isEmpty()) {
                            if (abortFlag.get()) {
                                System.out.println("TTS export aborted by user.");
                                return;
                            }
                            matrixClient.sendMarkdown(responseRoomId,
                                    "No chat logs found for the last " + messageCount + " messages to export from " + exportRoomId + ".");
                            return;
                        }

                        if (abortFlag.get()) return;

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
                            for (String line : ttsLines) {
                                if (abortFlag.get()) {
                                    System.out.println("TTS export aborted during file write.");
                                    return;
                                }
                                w.write(line + "\n");
                            }
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
                    } finally {
                        runningOperations.remove(sender);
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
            // Remove timestamp [yyyy-MM-dd HH:mm]
            String noTimestamp = line.replaceAll("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\]", "").trim();
            
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

    private void handleHelp(String responseRoomId, int page) {
        System.out.println("Received help command (page " + page + ")");
        String helpText;
        switch (page) {
            case 1:
                helpText = "**Search Commands (Page 1/3)**\n" +
                        "* `!search [<hours>h|<days>d] [user:@mxid] <query>` - Matrix native search (paginated, filter by user)\n" +
                        "* `!page <n>` - Jump to page n of search results\n" +
                        "* `!semantic <hours>h <query>` - AI-free semantic search using local embeddings\n" +
                        "* `!grep <hours>h <pattern>` - Pattern-based search (paginated)\n" +
                        "* `!searchtext <hours>h <term1 term2 ...>` - AND-term search (paginated)\n" +
                        "* `!media <hours>h <pattern>` - Search for file attachments\n\n" +
                        "Use `!help 2` for AI commands, `!help 3` for other commands";
                break;
            case 2:
                helpText = "**AI Commands (Page 2/3)**\n" +
                        "* `!ask [question]` - Query AI backend with up to 12k tokens of history (no timestamps)\n" +
                        "* `!summary <link or count or duration> [question]` - Condensed summary (No chunking)\n" +
                        "* `!isummary <link or count or duration> [question]` - Condensed summary with images (Vision ArliAI)\n" +
                        "* `!overview <link or count or duration> [question]` - Detailed overview (No chunking)\n" +
                        "* `!ioverview <link or count or duration> [question]` - Detailed overview with images (Vision ArliAI)\n" +
                        "* `!tldr <link or count or duration> [question]` - Very concise summary in bullet points, reading time under 15s. May append [question] for focused output.\n" +
                        "* `!debugai <link or count or duration> [question]` - Query AI backend with a custom prompt via question or chat logs\n" +
                        "* `!arliai <model> <prompt>` - Query ArliAI with specific model (fuzzy matched)\n" +
                        "* `!debugarliai <model> [params...] <prompt>` - Query ArliAI with custom API parameters\n" +
                        "* `!aisearch <hours>h <query>` - AI-powered agentic search (files, images, videos, conversations)\n\n" +
                        "**Quality-filtered variants** (ignores specific spammy user):\n" +
                        "* `!qtldr`, `!qsummary`, `!qoverview`, `!qask` - Same as above but with quality filtering\n\n" +
                        "Use `!help 1` for search commands, `!help 3` for other commands";
                break;
            case 3:
            default:
                helpText = "**Other Commands (Page 3/3)**\n" +
                        "* `!last` - Show your last message and read receipt status in export room (PRIMARY)\n" +
                        "* `!ping` - Measure and report ping latency\n" +
                        "* `!testcommand` - Test if the bot is responding\n" +
                        "* `!timezone <TZ or Time>` - Set your preferred timezone\n" +
                        "* `!export <count>` - Export last N messages (e.g., `!export 100`)\n" +
                        "* `!export <duration>h` - Export chat history by hours (e.g., `!export 24h`)\n" +
                        "* `!export <link> [+|-<count>]` - Export from message link with optional +/- count (e.g., `!export https://matrix.to/#/.../$eventId +50`)\n" +
                        "* `!ttsexport <count>` - Export messages with TTS-friendly formatting\n" +
                        "* `!ttsexport <duration>h` - Export messages from last specified hours with TTS-friendly formatting\n" +
                        "* `!lastsummary [question]` - Summarize all unread messages (uses saved TZ)\n" +
                        "* `!autolast [public]` - Toggle automatic last message notification (DM by default, use 'public' to send to channel)\n" +
                        "* `!autotldr [public]` - Toggle automatic AI TLDR notification (DM by default, use 'public' to send to channel)\n" +
                        "* `!abort` - Abort currently running operations\n\n" +
                        "Use `!help 1` for search commands, `!help 2` for AI commands";
                break;
        }
        matrixClient.sendMarkdown(responseRoomId, helpText);
    }
}
