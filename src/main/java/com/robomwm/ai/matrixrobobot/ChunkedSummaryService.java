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

    private static class SummarySlice {
        final String text;
        final long startTimestamp;
        final long endTimestamp;
        final String startLabel;
        final String endLabel;

        SummarySlice(String text, long startTimestamp, long endTimestamp, String startLabel, String endLabel) {
            this.text = text;
            this.startTimestamp = startTimestamp;
            this.endTimestamp = endTimestamp;
            this.startLabel = startLabel;
            this.endLabel = endLabel;
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
        final int timeoutSeconds;
        final boolean skipSystem;

        InternalQueryConfig(Backend preferredBackend, String arliModel, String cerebrasModel, int timeoutSeconds,
                boolean skipSystem) {
            this.preferredBackend = preferredBackend;
            this.arliModel = arliModel;
            this.cerebrasModel = cerebrasModel;
            this.timeoutSeconds = timeoutSeconds;
            this.skipSystem = skipSystem;
        }
    }

    public ChunkedSummaryService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
            String arliApiKey, String cerebrasApiKey) {
        super(client, mapper, homeserver, accessToken, arliApiKey, cerebrasApiKey);
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
            int preferredChunkCount = calculatePreferredChunkCount(contextWindowInfo);

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
            InternalQueryConfig config = new InternalQueryConfig(
                    preferredBackend,
                    arliModel,
                    cerebrasModel,
                    timeoutSeconds,
                    Prompts.DEBUGAI_PREFIX.equals(promptPrefix));

            int contextLimit = contextWindowInfo != null ? contextWindowInfo.limitTokens : MAX_CONTEXT_TOKENS;
            int chunkBudget = estimateChunkContentBudget(question, promptPrefix, contextLimit);
            List<ChunkRange> initialChunks = planLogChunks(history.logs, history.timestamps, chunkBudget, estimatorScale,
                    preferredChunkCount);
            if (initialChunks.isEmpty()) {
                super.handleContextExceeded(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag,
                        preferredBackend, forcedModel, timeoutSeconds, statusEventId, contextExceededMessage);
                return;
            }

            List<SummarySlice> partialSummaries = new ArrayList<>();
            int totalChunks = initialChunks.size();
            for (int i = 0; i < totalChunks; i++) {
                if (abortFlag != null && abortFlag.get()) {
                    return;
                }

                ChunkRange chunk = initialChunks.get(i);
                String chunkFooter = "Chunk " + (i + 1) + "/" + totalChunks;
                if (statusEventId != null) {
                    matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                            "Smart chunked summary...\n" + chunkFooter);
                }

                List<String> chunkLogs = new ArrayList<>(history.logs.subList(chunk.startIndex, chunk.endIndex));
                List<Long> chunkTimestamps = subListOrEmpty(history.timestamps, chunk.startIndex, chunk.endIndex);
                partialSummaries.add(summarizeLogChunk(chunkLogs, chunkTimestamps, question, promptPrefix, config,
                        abortFlag, responseRoomId, streamEventIdHolder, chunkFooter, estimatorScale, contextLimit));
            }

            SummarySlice finalSummary = appendSummarySlices(partialSummaries);
            String finalAnswer = appendMessageLink(finalSummary.text, exportRoomId, history.firstEventId);

            if (statusEventId != null) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                        "Smart chunked summary complete (" + partialSummaries.size() + " chunk"
                                + (partialSummaries.size() == 1 ? "" : "s") + ").");
            }
            matrixClient.sendMarkdown(responseRoomId, finalAnswer);
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

    private SummarySlice summarizeLogChunk(List<String> logs, List<Long> timestamps, String question,
            String promptPrefix, InternalQueryConfig config, AtomicBoolean abortFlag, String responseRoomId,
            String[] streamEventIdHolder, String footer, double estimatorScale, int contextLimit) throws Exception {
        if (abortFlag != null && abortFlag.get()) {
            throw new Exception("Aborted");
        }

        String directPrompt = buildChunkPrompt(question, logs, promptPrefix);
        try {
            if (estimateTotalPromptTokens(directPrompt, config.skipSystem) <= contextLimit) {
                String answer = executeInternalPrompt(directPrompt, config, abortFlag, responseRoomId, streamEventIdHolder, footer);
                return createSummarySlice(answer, logs, timestamps);
            }
        } catch (InternalContextExceededException ignored) {
            // Estimator can be low. Fall through to split path.
        }

        if (logs.size() <= 1) {
            throw new Exception("Single message chunk still exceeds context budget.");
        }

        int chunkBudget = estimateChunkContentBudget(question, promptPrefix, contextLimit);
        List<ChunkRange> childRanges = planLogChunks(logs, timestamps, chunkBudget, estimatorScale, -1);
        if (childRanges.size() <= 1) {
            childRanges = forceSplitRanges(logs.size());
        }

        List<SummarySlice> childSummaries = new ArrayList<>();
        for (ChunkRange childRange : childRanges) {
            List<String> childLogs = new ArrayList<>(logs.subList(childRange.startIndex, childRange.endIndex));
            List<Long> childTimestamps = subListOrEmpty(timestamps, childRange.startIndex, childRange.endIndex);
            childSummaries.add(summarizeLogChunk(childLogs, childTimestamps, question, promptPrefix, config,
                    abortFlag, responseRoomId, streamEventIdHolder, footer + " (split)", estimatorScale, contextLimit));
        }

        return appendSummarySlices(childSummaries);
    }

    private String executeInternalPrompt(String prompt, InternalQueryConfig config, AtomicBoolean abortFlag,
            String responseRoomId, String[] streamEventIdHolder, String footer) throws Exception {
        if (abortFlag != null && abortFlag.get()) {
            throw new Exception("Aborted");
        }

        boolean tryArli = config.preferredBackend == Backend.AUTO || config.preferredBackend == Backend.ARLIAI;
        boolean tryCerebras = config.preferredBackend == Backend.AUTO || config.preferredBackend == Backend.CEREBRAS;
        boolean attempted = false;
        boolean allContextExceeded = true;
        Exception lastNonContextError = null;

        if (tryArli) {
            attempted = true;
            try {
                return callArliAIStreamingToEvent(prompt, config.arliModel, config.skipSystem, responseRoomId,
                        streamEventIdHolder, footer, config.timeoutSeconds, abortFlag, true);
            } catch (Exception e) {
                if (!isContextExceededMessage(e.getMessage())) {
                    allContextExceeded = false;
                    lastNonContextError = e;
                    if (config.preferredBackend == Backend.ARLIAI) {
                        throw e;
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
                return callCerebras(prompt, config.cerebrasModel, config.skipSystem);
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
        String basePrompt = buildChunkPrompt(question, Collections.emptyList(), promptPrefix);
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
        for (String log : logs) {
            int estimated = RoomHistoryManager.estimateTokens(log) + 1;
            lineTokens.add(Math.max(1, (int) Math.ceil(estimated * estimatorScale)));
        }

        if (preferredChunkCount > 1) {
            return planLogChunksByTargetCount(logs, timestamps, tokenBudget, lineTokens, preferredChunkCount);
        }

        int start = 0;
        while (start < logs.size()) {
            int hardEnd = start;
            int currentTokens = 0;
            while (hardEnd < logs.size()) {
                int nextTokens = lineTokens.get(hardEnd);
                if (hardEnd > start && currentTokens + nextTokens > tokenBudget) {
                    break;
                }
                currentTokens += nextTokens;
                hardEnd++;
                if (hardEnd == logs.size()) {
                    break;
                }
            }

            if (hardEnd <= start) {
                hardEnd = Math.min(start + 1, logs.size());
            }

            int splitEnd = chooseSmartSplitEnd(timestamps, start, hardEnd);
            if (splitEnd <= start || splitEnd > hardEnd) {
                splitEnd = hardEnd;
            }

            chunks.add(new ChunkRange(start, splitEnd));
            start = splitEnd;
        }

        return chunks;
    }

    private List<ChunkRange> planLogChunksByTargetCount(List<String> logs, List<Long> timestamps, int tokenBudget,
            List<Integer> lineTokens, int preferredChunkCount) {
        List<ChunkRange> chunks = new ArrayList<>();
        int[] suffixTotals = new int[lineTokens.size() + 1];
        for (int i = lineTokens.size() - 1; i >= 0; i--) {
            suffixTotals[i] = suffixTotals[i + 1] + lineTokens.get(i);
        }

        int start = 0;
        int remainingChunks = preferredChunkCount;
        while (start < logs.size()) {
            if (remainingChunks <= 1) {
                chunks.add(new ChunkRange(start, logs.size()));
                break;
            }

            int remainingTokens = suffixTotals[start];
            int targetTokens = Math.max(1, (int) Math.ceil(remainingTokens / (double) remainingChunks));
            int softTarget = Math.min(tokenBudget, targetTokens);

            int currentTokens = 0;
            int softEnd = start;
            int hardEnd = start;

            while (hardEnd < logs.size()) {
                int nextTokens = lineTokens.get(hardEnd);
                if (hardEnd > start && currentTokens + nextTokens > tokenBudget) {
                    break;
                }
                currentTokens += nextTokens;
                hardEnd++;
                if (currentTokens >= softTarget && softEnd == start) {
                    softEnd = hardEnd;
                }
            }

            if (hardEnd <= start) {
                hardEnd = Math.min(start + 1, logs.size());
            }
            if (softEnd <= start) {
                softEnd = hardEnd;
            }

            int splitEnd = chooseSmartSplitEnd(timestamps, start, hardEnd);
            if (splitEnd < softEnd) {
                splitEnd = chooseSmartSplitEnd(timestamps, softEnd - 1, hardEnd);
            }
            if (splitEnd <= start || splitEnd > hardEnd) {
                splitEnd = hardEnd;
            }

            chunks.add(new ChunkRange(start, splitEnd));
            start = splitEnd;
            remainingChunks--;
        }

        return chunks;
    }

    private int chooseSmartSplitEnd(List<Long> timestamps, int start, int hardEnd) {
        if (timestamps == null || timestamps.size() < hardEnd || hardEnd - start < 3) {
            return hardEnd;
        }

        List<Long> gaps = new ArrayList<>();
        for (int i = start; i < hardEnd - 1; i++) {
            gaps.add(Math.max(0L, timestamps.get(i + 1) - timestamps.get(i)));
        }
        if (gaps.isEmpty()) {
            return hardEnd;
        }

        long medianGap = median(gaps);
        long maxGap = 0L;
        for (long gap : gaps) {
            if (gap > maxGap) {
                maxGap = gap;
            }
        }

        int preferredBoundaryFloor = start + Math.max(1, ((hardEnd - start) * 2) / 5);
        int bestBoundary = -1;
        long bestGap = -1L;
        for (int i = Math.max(start, preferredBoundaryFloor); i < hardEnd - 1; i++) {
            long gap = Math.max(0L, timestamps.get(i + 1) - timestamps.get(i));
            if (gap > bestGap || (gap == bestGap && i + 1 > bestBoundary)) {
                bestGap = gap;
                bestBoundary = i + 1;
            }
        }

        long meaningfulGap = Math.max(medianGap * 2L, maxGap / 3L);
        if (bestBoundary > start && bestGap >= meaningfulGap) {
            return bestBoundary;
        }

        int latestStrongBoundary = -1;
        long latestStrongGap = -1L;
        for (int i = start; i < hardEnd - 1; i++) {
            long gap = Math.max(0L, timestamps.get(i + 1) - timestamps.get(i));
            if (gap >= Math.max(1L, medianGap * 3L) && i + 1 > latestStrongBoundary) {
                latestStrongBoundary = i + 1;
                latestStrongGap = gap;
            }
        }

        if (latestStrongBoundary > start && latestStrongGap > 0L
                && latestStrongBoundary - start >= Math.max(1, (hardEnd - start) / 3)) {
            return latestStrongBoundary;
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

    private List<ChunkRange> forceSplitRanges(int size) {
        List<ChunkRange> ranges = new ArrayList<>();
        if (size <= 0) {
            return ranges;
        }
        if (size == 1) {
            ranges.add(new ChunkRange(0, 1));
            return ranges;
        }

        int mid = Math.max(1, size / 2);
        ranges.add(new ChunkRange(0, mid));
        ranges.add(new ChunkRange(mid, size));
        return ranges;
    }

    private SummarySlice createSummarySlice(String text, List<String> logs, List<Long> timestamps) {
        long startTimestamp = getTimestampOrFallback(timestamps, 0);
        long endTimestamp = getTimestampOrFallback(timestamps, timestamps == null ? -1 : timestamps.size() - 1);
        String startLabel = extractTimestampLabel(logs.isEmpty() ? null : logs.get(0));
        String endLabel = extractTimestampLabel(logs.isEmpty() ? null : logs.get(logs.size() - 1));
        return new SummarySlice(text, startTimestamp, endTimestamp, startLabel, endLabel);
    }

    private SummarySlice mergeSliceMetadata(String text, List<SummarySlice> slices) {
        SummarySlice first = slices.get(0);
        SummarySlice last = slices.get(slices.size() - 1);
        return new SummarySlice(text, first.startTimestamp, last.endTimestamp, first.startLabel, last.endLabel);
    }

    private long getTimestampOrFallback(List<Long> timestamps, int index) {
        if (timestamps == null || timestamps.isEmpty() || index < 0 || index >= timestamps.size()) {
            return 0L;
        }
        return timestamps.get(index);
    }

    private List<Long> subListOrEmpty(List<Long> values, int start, int end) {
        if (values == null || values.size() < end) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values.subList(start, end));
    }

    private String buildChunkPrompt(String question, List<String> logs, String promptPrefix) {
        return "This is one chunk of a larger chat log. Summarize only this chunk for later merging. "
                + "Keep output dense and factual. Preserve timestamps, decisions, useful resources, and unresolved questions. "
                + "Avoid intro and outro. Do not echo raw messages line-by-line unless explicitly asked. "
                + "Do not mention chunk numbers.\n\n"
                + buildPrompt(question, logs, promptPrefix);
    }

    private SummarySlice appendSummarySlices(List<SummarySlice> slices) throws Exception {
        if (slices == null || slices.isEmpty()) {
            throw new Exception("No summaries produced for append.");
        }
        if (slices.size() == 1) {
            return slices.get(0);
        }

        StringBuilder combined = new StringBuilder();
        for (SummarySlice slice : slices) {
            if (slice == null || slice.text == null || slice.text.trim().isEmpty()) {
                continue;
            }
            if (combined.length() > 0) {
                combined.append("\n\n");
            }
            combined.append(slice.text.trim());
        }
        return mergeSliceMetadata(combined.toString(), slices);
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
        return Math.max(0.25, Math.min(1.25, scale));
    }

    private int calculatePreferredChunkCount(ContextWindowInfo contextWindowInfo) {
        if (contextWindowInfo == null || contextWindowInfo.usedTokens <= 0 || contextWindowInfo.limitTokens <= 0) {
            return -1;
        }
        return Math.max(1, (int) Math.ceil(contextWindowInfo.usedTokens / (double) contextWindowInfo.limitTokens));
    }
}
