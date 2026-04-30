package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Summary-capable AI service with context-overflow fallback.
 * Splits logs into large chunks near timestamp lulls, summarizes each chunk by calling the base AIService.
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
                
                RoomHistoryManager.ChatLogsResult chunkHistory = new RoomHistoryManager.ChatLogsResult(chunkLogs,
                        (chunkEventIds != null && !chunkEventIds.isEmpty()) ? chunkEventIds.get(0) : history.firstEventId);
                chunkHistory.timestamps = chunkTimestamps;
                chunkHistory.eventIds = chunkEventIds;

                // Call the base performAIQuery for each chunk as if it were a normal sequential command
                performAIQuery(responseRoomId, exportRoomId, chunkHistory, question, promptPrefix, abortFlag,
                        preferredBackend, forcedModel, timeoutSeconds, null, chunkFooter);
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

        if (maxGap > 60000) {
            return bestBoundary;
        }

        return hardEnd;
    }

    private <T> List<T> subListOrEmpty(List<T> values, int start, int end) {
        if (values == null || values.size() < end) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values.subList(start, end));
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
