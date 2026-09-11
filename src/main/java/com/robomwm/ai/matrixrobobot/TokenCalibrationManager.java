package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic estimator + self-calibrating factor persisted to disk.
 * Base is plain length/max-observed-chars-per-token per family (gpt 2.75,
 * qwen 2.46), then retries with less on 403 context length.
 * EMA update: factor = factor*(1-ALPHA) + ratio*ALPHA where ratio = actual/estimatedRaw.
 *
 * Factors are stored per tokenizer family because token counts differ consistently
 * between families no matter the provider (e.g. all qwen models tokenize one way,
 * all gpt models another). Family is derived from the model name.
 */
public class TokenCalibrationManager {
    private static final Path FILE = Paths.get("token_calibration.json");
    private static final Path HISTORY_FILE = Paths.get("token_calibration_history.jsonl");
    private static final double DEFAULT_FACTOR = 1.0;
    private static final double ALPHA = 0.3;
    private static final double MIN_FACTOR = 1.0;
    private static final double MAX_FACTOR = 3.0;

    public static final String FAMILY_QWEN = "qwen";
    public static final String FAMILY_GPT = "gpt";
    public static final String FAMILY_DEFAULT = "default";

    // Regex to extract actual prompt tokens from ArliAI 403: "exceeded ... (25487/12288)"
    private static final Pattern CONTEXT_PATTERN = Pattern.compile("\\((\\d+)\\s*/\\s*\\d+\\s*\\)");
    private static final Pattern GROQ_REQUESTED_PATTERN = Pattern.compile("Requested\\s+(\\d+)");
    private static final Pattern GROQ_LIMIT_PATTERN = Pattern.compile("Limit\\s+(\\d+)");

    // Base estimator: plain chars/token, one constant per tokenizer family.
    // Values are the max observed chars/token from failure history (rounded):
    // gpt max 2.751 (mean 2.621, n=12), qwen max 2.460 (mean 2.407, n=13).
    // Max = aggressive start (gather most context); failure-only feedback can
    // only ratchet the factor upward, so starting hot maximizes context while
    // staying self-correcting. Per-family factor absorbs drift/content mix.
    private static final double CHARS_PER_TOKEN_DEFAULT = 2.75;
    private static final double CHARS_PER_TOKEN_QWEN = 2.46;
    private static final double CHARS_PER_TOKEN_GPT = 2.75;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Double> factors = new HashMap<>();
    private final Map<String, Integer> samples = new HashMap<>();

    private static volatile TokenCalibrationManager INSTANCE;

    public static TokenCalibrationManager getInstance() {
        if (INSTANCE == null) {
            synchronized (TokenCalibrationManager.class) {
                if (INSTANCE == null) INSTANCE = new TokenCalibrationManager();
            }
        }
        return INSTANCE;
    }

    /** Derive calibration family from a model name. Consistent across providers. */
    public static String familyForModel(String model) {
        if (model == null) return FAMILY_DEFAULT;
        String lower = model.toLowerCase();
        if (lower.contains("qwen")) return FAMILY_QWEN;
        if (lower.contains("gpt")) return FAMILY_GPT;
        return FAMILY_DEFAULT;
    }

    private TokenCalibrationManager() {
        loadFactors();
    }

    private double clamp(double f) {
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, f));
    }

    private void putLoaded(String family, double f, int s) {
        factors.put(family, clamp(f));
        samples.put(family, Math.max(0, s));
    }

    private void loadFactors() {
        putLoaded(FAMILY_DEFAULT, DEFAULT_FACTOR, 0);
        putLoaded(FAMILY_QWEN, DEFAULT_FACTOR, 0);
        putLoaded(FAMILY_GPT, DEFAULT_FACTOR, 0);
        if (!Files.exists(FILE)) return;
        try {
            String content = Files.readString(FILE);
            var node = mapper.readTree(content);
            if (node.has("factors")) {
                var fnode = node.path("factors");
                for (String family : new String[]{FAMILY_DEFAULT, FAMILY_QWEN, FAMILY_GPT}) {
                    var entry = fnode.path(family);
                    if (!entry.isMissingNode()) {
                        double f = entry.path("factor").asDouble(DEFAULT_FACTOR);
                        int s = entry.path("samples").asInt(0);
                        putLoaded(family, f, s);
                    }
                }
                // Preserve any extra families already on disk.
                var it = fnode.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    if (!factors.containsKey(e.getKey())) {
                        double f = e.getValue().path("factor").asDouble(DEFAULT_FACTOR);
                        int s = e.getValue().path("samples").asInt(0);
                        putLoaded(e.getKey(), f, s);
                    }
                }
                System.out.println("Loaded token calibration factors=" + factors + " samples=" + samples);
            } else if (node.has("factor")) {
                // Legacy single-factor file: seed every family with it so learned
                // history is not thrown away on upgrade.
                double f = node.path("factor").asDouble(DEFAULT_FACTOR);
                int s = node.path("samples").asInt(0);
                System.out.println("Migrating legacy token calibration factor=" + f + " samples=" + s + " to per-family factors");
                putLoaded(FAMILY_DEFAULT, f, s);
                putLoaded(FAMILY_QWEN, f, s);
                putLoaded(FAMILY_GPT, f, s);
                saveFactors();
            }
        } catch (IOException e) {
            System.err.println("Failed to load token calibration: " + e.getMessage());
        }
    }

    private synchronized void saveFactors() {
        try {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            Map<String, Object> fmap = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Double> e : factors.entrySet()) {
                fmap.put(e.getKey(), Map.of("factor", e.getValue(), "samples", samples.getOrDefault(e.getKey(), 0)));
            }
            out.put("factors", fmap);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
            Files.writeString(FILE, json);
        } catch (IOException e) {
            System.err.println("Failed to save token calibration: " + e.getMessage());
        }
    }

    public double getFactor() {
        return getFactor((String) null);
    }

    public synchronized double getFactor(String model) {
        return factors.getOrDefault(familyForModel(model), DEFAULT_FACTOR);
    }

    public synchronized int getSamples(String model) {
        return samples.getOrDefault(familyForModel(model), 0);
    }

    /** Base chars/token for a model (per tokenizer family, max-observed). */
    public static double baseCharsPerToken(String model) {
        String family = familyForModel(model);
        if (FAMILY_QWEN.equals(family)) return CHARS_PER_TOKEN_QWEN;
        if (FAMILY_GPT.equals(family)) return CHARS_PER_TOKEN_GPT;
        return CHARS_PER_TOKEN_DEFAULT;
    }

    /** Heuristic raw estimate without calibration: plain length/base. */
    public static int estimateRaw(String text) {
        return estimateRaw(text, (String) null);
    }

    /** Per-family raw estimate; family base keeps history ratios comparable. */
    public static int estimateRaw(String text, String model) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / baseCharsPerToken(model)));
    }

    /** Calibrated estimate used by RoomHistoryManager (default family, backward compat). */
    public int estimateTokens(String text) {
        return estimateTokens(text, (String) null);
    }

    /** Calibrated estimate for a specific model (per-family base + factor). */
    public int estimateTokens(String text, String model) {
        int raw = estimateRaw(text, model);
        return (int) Math.ceil(raw * getFactor(model));
    }

    /**
     * Conservative estimate across several candidate models: the max calibrated
     * estimate, so gathered history fits the most restrictive tokenizer family.
     */
    public int estimateTokensConservative(String text, Collection<String> models) {
        int best = estimateTokens(text, (String) null);
        if (models != null) {
            for (String m : models) {
                best = Math.max(best, estimateTokens(text, m));
            }
        }
        return best;
    }

    public static boolean isContextLengthError(String errorMsg) {
        return errorMsg != null && errorMsg.contains("exceeded the maximum context length");
    }

    public static boolean isGroqTpmError(String errorMsg) {
        if (errorMsg == null) return false;
        // Exclude output-token limits (OTPM) – those report max_tokens, not prompt tokens
        String lower = errorMsg.toLowerCase();
        if (lower.contains("output tokens per minute") || lower.contains("otpm")) return false;
        return errorMsg.contains("tokens per minute") && GROQ_REQUESTED_PATTERN.matcher(errorMsg).find();
    }

    public static boolean isCalibrationError(String errorMsg) {
        return isContextLengthError(errorMsg) || isGroqTpmError(errorMsg);
    }

    public static Integer extractActualTokens(String errorMsg) {
        if (errorMsg == null) return null;
        Matcher matcher = CONTEXT_PATTERN.matcher(errorMsg);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Integer extractGroqRequested(String errorMsg) {
        if (errorMsg == null) return null;
        Matcher m = GROQ_REQUESTED_PATTERN.matcher(errorMsg);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Integer extractGroqLimit(String errorMsg) {
        if (errorMsg == null) return null;
        Matcher m = GROQ_LIMIT_PATTERN.matcher(errorMsg);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static Integer extractActualTokensForCalibration(String errorMsg) {
        Integer ctx = extractActualTokens(errorMsg);
        if (ctx != null) return ctx;
        return extractGroqRequested(errorMsg);
    }

    public static Integer extractLimitForCalibration(String errorMsg) {
        if (isContextLengthError(errorMsg)) {
            Matcher m = CONTEXT_PATTERN.matcher(errorMsg);
            if (m.find()) {
                // denominator is inside parentheses e.g. (25487/12288) — extract second number
                Pattern denom = Pattern.compile("\\(\\d+\\s*/\\s*(\\d+)\\s*\\)");
                Matcher dm = denom.matcher(errorMsg);
                if (dm.find()) {
                    try { return Integer.parseInt(dm.group(1)); } catch (NumberFormatException ignored) {}
                }
            }
            return 12288;
        }
        if (isGroqTpmError(errorMsg)) {
            Integer lim = extractGroqLimit(errorMsg);
            return lim != null ? lim : 8000;
        }
        return null;
    }

    /**
     * Compute how many of {@code currentSize} messages to keep so the prompt fits
     * {@code limit} tokens with headroom. Returns {@code currentSize} when no trim
     * applies. Pure function for reuse across query paths and tests.
     */
    public static int computeTrimmedSize(int currentSize, Integer actual, Integer limit) {
        return computeTrimmedSize(currentSize, actual, limit, 0.85, 0.3, 0.85, 10);
    }

    static int computeTrimmedSize(int currentSize, Integer actual, Integer limit,
            double headroom, double minRatio, double maxRatio, int minSize) {
        if (actual == null || limit == null || currentSize <= minSize) return currentSize;
        double targetRatio = limit * headroom / (double) actual;
        targetRatio = Math.max(minRatio, Math.min(maxRatio, targetRatio));
        int newSize = Math.max(minSize, (int) (currentSize * targetRatio));
        return Math.min(newSize, currentSize);
    }

    /**
     * Update calibration from a failed prompt and its actual token count.
     * Estimator is for entire prompt sent to AI provider – no assumptions/exceptions.
     * Logs raw, calibrated, actual, new factor for future review (linear EMA vs log/exp).
     * Returns new factor.
     */
    public synchronized double recordFromError(String prompt, String errorMsg) {
        return recordFromError(prompt, errorMsg, (String) null);
    }

    /** Per-model variant: updates only that model's tokenizer family. */
    public synchronized double recordFromError(String prompt, String errorMsg, String model) {
        String family = familyForModel(model);
        double old = factors.getOrDefault(family, DEFAULT_FACTOR);
        Integer actual = extractActualTokensForCalibration(errorMsg);
        if (actual == null) {
            System.out.println("Calibration: could not parse actual tokens from: " + errorMsg);
            return old;
        }
        int estimatedRaw = estimateRaw(prompt, model);
        if (estimatedRaw == 0) return old;
        double rawBase = baseCharsPerToken(model);
        int estimatedCalibrated = (int) Math.ceil(estimatedRaw * old);
        double ratio = (double) actual / estimatedRaw;
        double rawRatio = ratio;
        // ratio <1 means we overestimated, don't reduce below MIN_FACTOR
        if (ratio < 1.0) ratio = 1.0;
        if (ratio > 3.0) ratio = 3.0; // clamp outlier
        double factor = old * (1 - ALPHA) + ratio * ALPHA;
        factor = clamp(factor);
        factors.put(family, factor);
        Integer limit = extractLimitForCalibration(errorMsg);
        String limitStr = limit != null ? String.valueOf(limit) : "unknown";
        // Detailed log for future review: raw vs calibrated vs actual to judge if linear factor sufficient
        // Persist to file (not chat) per user request; also keep stdout for journal
        String detail = "Calibration update [" + family + (model != null ? "/" + model : "") + "]: estimatedRaw=" + estimatedRaw + " estimatedCalibrated=" + estimatedCalibrated + " (factor " + String.format("%.4f", old) + ") actual=" + actual + " limit=" + limitStr +
                " rawRatio=" + String.format("%.4f", rawRatio) + " clampedRatio=" + String.format("%.4f", ratio) + " factor " + String.format("%.4f", old) + " -> " + String.format("%.4f", factor) +
                " promptChars=" + (prompt != null ? prompt.length() : 0);
        System.out.println(detail);
        appendHistory(family, model, estimatedRaw, rawBase, estimatedCalibrated, old, actual, limit, rawRatio, ratio, factor, prompt != null ? prompt.length() : 0);
        samples.put(family, samples.getOrDefault(family, 0) + 1);
        saveFactors();
        return factor;
    }

    private synchronized void appendHistory(String family, String model, int estimatedRaw, double rawBase, int estimatedCalibrated, double oldFactor, int actual, Integer limit, double rawRatio, double clampedRatio, double newFactor, int promptChars) {
        try {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("timestamp", java.time.Instant.now().toString());
            entry.put("family", family);
            if (model != null) entry.put("model", model);
            entry.put("estimatedRaw", estimatedRaw);
            entry.put("rawBase", rawBase);
            entry.put("estimatedCalibrated", estimatedCalibrated);
            entry.put("oldFactor", oldFactor);
            entry.put("actual", actual);
            entry.put("limit", limit);
            entry.put("rawRatio", rawRatio);
            entry.put("clampedRatio", clampedRatio);
            entry.put("newFactor", newFactor);
            entry.put("promptChars", promptChars);
            String line = mapper.writeValueAsString(entry);
            Files.writeString(HISTORY_FILE, line + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to append calibration history: " + e.getMessage());
        }
    }

    /** For testing/manual adjustment (default family). */
    public synchronized void setFactor(double newFactor) {
        setFactor(null, newFactor);
    }

    /** For testing/manual adjustment of one family. */
    public synchronized void setFactor(String model, double newFactor) {
        String family = familyForModel(model);
        factors.put(family, clamp(newFactor));
        samples.put(family, 0);
        saveFactors();
    }

    /** Reset singleton (tests only). */
    static synchronized void resetForTests() {
        INSTANCE = null;
    }
}
