package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Summary-capable AI service with context-overflow fallback.
 * First tries normal single-shot summary. If all attempted backends exceed context,
 * splits logs into large chunks near timestamp lulls, summarizes each chunk, then
 * merges chunk summaries into a final answer.
 */
public class ChunkedSummaryService extends AIService {
    private static final int MAX_CONTEXT_TOKENS = 12000;
    private static final int CHAT_FORMAT_OVERHEAD_TOKENS = 20;
    private static final int MIN_CONTENT_TOKENS = 1000;

    protected static class ChunkRange {
        final int startIndex;
        final int endIndex;

        ChunkRange(int startIndex, int endIndex) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }



    private static class ContextWindowInfo {
        final int usedTokens;
        final int limitTokens;

        ContextWindowInfo(int usedTokens, int limitTokens) {
            this.usedTokens = usedTokens;
            this.limitTokens = limitTokens;
        }
    }

    private static class InternalContextExceededException extends Exception {
        InternalContextExceededException(String message) {
            super(message);
        }
    }

    private static class InternalQueryConfig {
        final Backend preferredBackend;
        final String arliModel;
        final String cerebrasModel;
        final String groqModel;
        final int timeoutSeconds;
        final boolean skipSystem;

        InternalQueryConfig(Backend preferredBackend, String arliModel, String cerebrasModel, String groqModel, int timeoutSeconds,
                boolean skipSystem) {
            this.preferredBackend = preferredBackend;
            this.arliModel = arliModel;
            this.cerebrasModel = cerebrasModel;
            this.groqModel = groqModel;
            this.timeoutSeconds = timeoutSeconds;
            this.skipSystem = skipSystem;
        }
    }

    public ChunkedSummaryService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
            String arliApiKey, String cerebrasApiKey, String groqApiKey) {
        super(client, mapper, homeserver, accessToken, arliApiKey, cerebrasApiKey, groqApiKey);
    }

    @Override
    protected void handleContextExceeded(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
            String question, String promptPrefix, AtomicBoolean abortFlag, Backend preferredBackend,
            String forcedModel, int timeoutSeconds, String statusEventId, String contextExceededMessage) {
        if (!supportsChunkFallback(promptPrefix) || history == null || history.logs == null || history.logs.isEmpty()) {
            super.handleContextExceeded(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag,
                    preferredBackend, forcedModel, timeoutSeconds, statusEventId, contextExceededMessage);
            return;
        }

        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            if (abortFlag != null && abortFlag.get()) {
                return;
            }

            ContextWindowInfo contextWindowInfo = parseContextWindowInfo(contextExceededMessage);
            double estimatorScale = calculateEstimatorScale(history, question, promptPrefix, contextWindowInfo);

            int contextLimit = contextWindowInfo != null ? contextWindowInfo.limitTokens : MAX_CONTEXT_TOKENS;
            int chunkBudget = estimateChunkContentBudget(question, promptPrefix, contextLimit);
            int preferredChunkCount = calculatePreferredChunkCount(history, estimatorScale, chunkBudget);

            System.out.println("ChunkedSummary: used=" + (contextWindowInfo != null ? contextWindowInfo.usedTokens : "null")
                    + " limit=" + contextLimit + " budget=" + chunkBudget + " scale=" + estimatorScale
                    + " preferredCount=" + preferredChunkCount);

            if (statusEventId != null) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                        "Context exceeded. Switching to smart chunked summary...");
            } else {
                matrixClient.sendNotice(responseRoomId, "Context exceeded. Switching to smart chunked summary...");
            }

            String[] streamEventIdHolder = new String[]{statusEventId};
            String arliModel = (preferredBackend == Backend.ARLIAI && forcedModel != null)
                    ? forcedModel
                    : getRandomModel(ARLI_MODELS);
            String cerebrasModel = getRandomModel(CEREBRAS_MODELS);
            String groqModel = (preferredBackend == Backend.GROQ && forcedModel != null)
                    ? forcedModel
                    : GROQ_MODELS.get(0);
            InternalQueryConfig config = new InternalQueryConfig(
                    preferredBackend,
                    arliModel,
                    cerebrasModel,
                    groqModel,
                    timeoutSeconds,
                    Prompts.DEBUGAI_PREFIX.equals(promptPrefix));

            List<ChunkRange> initialChunks = planLogChunks(history.logs, history.timestamps, chunkBudget, estimatorScale,
                    preferredChunkCount);
            if (initialChunks.isEmpty()) {
                super.handleContextExceeded(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag,
                        preferredBackend, forcedModel, timeoutSeconds, statusEventId, contextExceededMessage);
                return;
            }


            int totalChunks = initialChunks.size();
            for (int i = 0; i < totalChunks; i++) {
                if (abortFlag != null && abortFlag.get()) {
                    return;
                }

                ChunkRange chunk = initialChunks.get(i);
                String chunkFooter = "Chunk " + (i + 1) + "/" + totalChunks;
                
                List<String> chunkLogs = new ArrayList<>(history.logs.subList(chunk.startIndex, chunk.endIndex));
                List<Long> chunkTimestamps = subListOrEmpty(history.timestamps, chunk.startIndex, chunk.endIndex);
                List<String> chunkEventIds = subListOrEmpty(history.eventIds, chunk.startIndex, chunk.endIndex);
                String chunkFirstEventId = (chunkEventIds != null && !chunkEventIds.isEmpty()) ? chunkEventIds.get(0) : history.firstEventId;

                summarizeLogChunk(chunkLogs, chunkTimestamps, chunkEventIds, question, promptPrefix, config,
                        abortFlag, responseRoomId, exportRoomId, chunkFirstEventId, chunkFooter, estimatorScale, contextLimit);
            }

            if (statusEventId != null) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                        "Smart chunked summary complete (" + totalChunks + " chunk"
                                + (totalChunks == 1 ? "" : "s") + ").");
            }
        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId,
                    "Chunked summary failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private boolean supportsChunkFallback(String promptPrefix) {
        return Prompts.SUMMARY_PREFIX.equals(promptPrefix)
                || Prompts.OVERVIEW_PREFIX.equals(promptPrefix)
                || Prompts.TLDR_PREFIX.equals(promptPrefix);
    }

    private void summarizeLogChunk(List<String> logs, List<Long> timestamps, List<String> eventIds, String question,
            String promptPrefix, InternalQueryConfig config, AtomicBoolean abortFlag, String responseRoomId,
            String exportRoomId, String firstEventId, String footer, double estimatorScale, int contextLimit) throws Exception {
        if (abortFlag != null && abortFlag.get()) {
            throw new Exception("Aborted");
        }

        String directPrompt = buildPrompt(question, logs, promptPrefix);
        String[] eventIdHolder = new String[]{null};
        String answer = executeInternalPrompt(directPrompt, config, abortFlag, responseRoomId, eventIdHolder, footer);
        String finalAnswer = appendMessageLink(answer, exportRoomId, firstEventId);
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], finalAnswer);
    }

    private String executeInternalPrompt(String prompt, InternalQueryConfig config, AtomicBoolean abortFlag,
            String responseRoomId, String[] streamEventIdHolder, String footer) throws Exception {
        if (abortFlag != null && abortFlag.get()) {
            throw new Exception("Aborted");
        }

        boolean tryGroq = config.preferredBackend == Backend.AUTO || config.preferredBackend == Backend.GROQ;
        boolean tryCerebras = config.preferredBackend == Backend.AUTO || config.preferredBackend == Backend.CEREBRAS;
        boolean tryArli = config.preferredBackend == Backend.AUTO || config.preferredBackend == Backend.ARLIAI;
        boolean attempted = false;
        boolean allContextExceeded = true;
        Exception lastNonContextError = null;

        if (tryGroq && groqApiKey != null && !groqApiKey.isEmpty()) {
            attempted = true;
            try {
                // Try compound
                return callGroqStreamingToEvent(prompt, config.groqModel, config.skipSystem, responseRoomId,
                        streamEventIdHolder, footer, config.timeoutSeconds, abortFlag, true);
            } catch (Exception e) {
                if (!isContextExceededMessage(e.getMessage())) {
                    if (config.preferredBackend != Backend.AUTO) throw e;
                }
                
                // Try compound-mini if AUTO
                if (config.preferredBackend == Backend.AUTO) {
                    try {
                        return callGroqStreamingToEvent(prompt, GROQ_MODELS.get(1), config.skipSystem, responseRoomId,
                                streamEventIdHolder, footer, config.timeoutSeconds, abortFlag, true);
                    } catch (Exception e2) {
                        if (!isContextExceededMessage(e2.getMessage())) {
                            // fall through
                        }
                    }
                }
            }
        }

        if (abortFlag != null && abortFlag.get()) {
            throw new Exception("Aborted");
        }

        if (tryCerebras && cerebrasApiKey != null && !cerebrasApiKey.isEmpty()) {
            attempted = true;
            try {
                if (streamEventIdHolder != null && streamEventIdHolder[0] != null) {
                    MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
                    String cerebrasNotice = "Using Cerebras...\n\n" + footer;
                    matrixClient.updateMarkdownNoticeMessage(responseRoomId, streamEventIdHolder[0], cerebrasNotice);
                }
                return callCerebras(prompt, config.cerebrasModel, config.skipSystem, config.timeoutSeconds);
            } catch (Exception e) {
                if (!isContextExceededMessage(e.getMessage())) {
                    allContextExceeded = false;
                    lastNonContextError = e;
                    if (config.preferredBackend == Backend.CEREBRAS) {
                        throw e;
                    }
                }
            }
        }

        if (attempted && allContextExceeded) {
            throw new InternalContextExceededException("All attempted backends exceeded context.");
        }
        if (lastNonContextError != null) {
            throw lastNonContextError;
        }
        throw new Exception("No AI backend available for chunked summary.");
    }

    private int estimateChunkContentBudget(String question, String promptPrefix, int contextLimit) {
        String basePrompt = buildPrompt(question, Collections.emptyList(), promptPrefix);
        int baseTokens = estimateTotalPromptTokens(basePrompt, false);
        return Math.max(MIN_CONTENT_TOKENS, contextLimit - baseTokens);
    }

    private int estimateTotalPromptTokens(String prompt, boolean skipSystem) {
        int total = RoomHistoryManager.estimateTokens(prompt) + CHAT_FORMAT_OVERHEAD_TOKENS;
        if (!skipSystem) {
            total += RoomHistoryManager.estimateTokens(Prompts.SYSTEM_OVERVIEW);
        }
        return total;
    }

    private List<ChunkRange> planLogChunks(List<String> logs, List<Long> timestamps, int tokenBudget, double estimatorScale,
            int preferredChunkCount) {
        List<ChunkRange> chunks = new ArrayList<>();
        if (logs == null || logs.isEmpty()) {
            return chunks;
        }

        List<Integer> lineTokens = new ArrayList<>(logs.size());
        long totalEstimated = 0;
        for (String log : logs) {
            int estimated = (int) Math.ceil((RoomHistoryManager.estimateTokens(log) + 1) * estimatorScale);
            lineTokens.add(Math.max(1, estimated));
            totalEstimated += estimated;
        }

        int count = preferredChunkCount;
        if (count <= 0) {
            count = (int) Math.ceil(totalEstimated / (double) tokenBudget);
        }
        count = Math.max(1, count);

        int targetTokensPerChunk = (int) Math.ceil(totalEstimated / (double) count);
        int effectiveBudget = Math.min(tokenBudget, (int) (targetTokensPerChunk * 1.1)); // allow 10% overflow for smart split

        int start = 0;
        while (start < logs.size()) {
            if (chunks.size() + 1 >= count) {
                chunks.add(new ChunkRange(start, logs.size()));
                break;
            }

            int currentTokens = 0;
            int hardEnd = start;
            while (hardEnd < logs.size()) {
                int nextTokens = lineTokens.get(hardEnd);
                if (hardEnd > start && currentTokens + nextTokens > effectiveBudget) {
                    break;
                }
                currentTokens += nextTokens;
                hardEnd++;
            }

            if (hardEnd <= start) {
                hardEnd = Math.min(start + 1, logs.size());
            }

            int splitEnd = chooseSmartSplitEnd(timestamps, start, hardEnd);
            chunks.add(new ChunkRange(start, splitEnd));
            start = splitEnd;
        }

        return chunks;
    }



    private int chooseSmartSplitEnd(List<Long> timestamps, int start, int hardEnd) {
        if (timestamps == null || timestamps.size() < hardEnd || hardEnd - start < 4) {
            return hardEnd;
        }

        // Look for the largest gap in the second half of the chunk
        int searchStart = start + (hardEnd - start) / 2;
        int bestBoundary = hardEnd;
        long maxGap = -1;

        for (int i = searchStart; i < hardEnd - 1; i++) {
            long gap = timestamps.get(i + 1) - timestamps.get(i);
            if (gap >= maxGap) {
                maxGap = gap;
                bestBoundary = i + 1;
            }
        }

        // Only use the gap if it's "significant" (e.g. > 1 minute or at least 5x the median of the search window)
        if (maxGap > 60000) {
            return bestBoundary;
        }

        return hardEnd;
    }

    private long median(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }

        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }





    private long getTimestampOrFallback(List<Long> timestamps, int index) {
        if (timestamps == null || timestamps.isEmpty() || index < 0 || index >= timestamps.size()) {
            return 0L;
        }
        return timestamps.get(index);
    }

    private <T> List<T> subListOrEmpty(List<T> values, int start, int end) {
        if (values == null || values.size() < end) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values.subList(start, end));
    }



    private String extractTimestampLabel(String logLine) {
        if (logLine == null || !logLine.startsWith("[")) {
            return "";
        }
        int end = logLine.indexOf(']');
        if (end <= 1) {
            return "";
        }
        return logLine.substring(1, end);
    }

    private ContextWindowInfo parseContextWindowInfo(String message) {
        if (message == null) {
            return null;
        }
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\((\\d+)/(\\d+)\\)").matcher(message);
            if (matcher.find()) {
                return new ContextWindowInfo(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private double calculateEstimatorScale(RoomHistoryManager.ChatLogsResult history, String question, String promptPrefix,
            ContextWindowInfo contextWindowInfo) {
        if (contextWindowInfo == null || contextWindowInfo.usedTokens <= 0 || history == null || history.logs == null) {
            return 1.0;
        }

        String oneShotPrompt = buildPrompt(question, history.logs, promptPrefix);
        int estimated = estimateTotalPromptTokens(oneShotPrompt, Prompts.DEBUGAI_PREFIX.equals(promptPrefix));
        if (estimated <= 0) {
            return 1.0;
        }

        double scale = contextWindowInfo.usedTokens / (double) estimated;
        return Math.max(0.25, Math.min(3.0, scale));
    }

    private int calculatePreferredChunkCount(RoomHistoryManager.ChatLogsResult history, double estimatorScale, int tokenBudget) {
        long totalLogTokens = 0;
        for (String log : history.logs) {
            totalLogTokens += (int) Math.ceil((RoomHistoryManager.estimateTokens(log) + 1) * estimatorScale);
        }

        return Math.max(1, (int) Math.ceil(totalLogTokens / (double) tokenBudget));
    }
}
