package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for handling debug AI queries with customizable API parameters.
 * Provides fine-grained control over Arli AI API settings.
 */
public class DebugAIService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final String arliApiKey;
    
    public static final List<String> ARLI_MODELS = Arrays.asList(
            "Qwen3.5-27B-Musica-v1",
            "Qwen3.5-27B-Vivid-Durian",
            "Qwen3.5-27B-Derestricted"
    );

    /**
     * Configuration class for debug API parameters.
     */
    public static class DebugConfig {
        // Sampling parameters
        public Double temperature;
        public Double topP;
        public Integer topK;
        public Double minP;

        // Repetition control
        public Double repetitionPenalty;
        public Double frequencyPenalty;
        public Double presencePenalty;

        // Token limits
        public Integer maxTokens;
        public Integer minTokens;

        // Other parameters
        public Integer seed;
        public List<String> stop;

        // System prompt control
        public Boolean skipSystem;

        // Model selection
        public String model;
        
        public DebugConfig() {
            // Set defaults
            this.temperature = null;
            this.topP = null;
            this.topK = null;
            this.minP = null;
            this.repetitionPenalty = null;
            this.frequencyPenalty = null;
            this.presencePenalty = null;
            this.maxTokens = null;
            this.minTokens = null;
            this.seed = null;
            this.stop = null;
            this.skipSystem = false;
            this.model = null;
        }
        
        /**
         * Build the API payload map from this config.
         */
        public Map<String, Object> toPayloadMap(String model, List<Map<String, String>> messages) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("stream", true);
            payload.put("output_kind", "delta");

            // Add optional parameters if set
            if (temperature != null) payload.put("temperature", temperature);
            if (topP != null) payload.put("top_p", topP);
            if (topK != null) payload.put("top_k", topK);
            if (minP != null) payload.put("min_p", minP);
            if (repetitionPenalty != null) payload.put("repetition_penalty", repetitionPenalty);
            if (frequencyPenalty != null) payload.put("frequency_penalty", frequencyPenalty);
            if (presencePenalty != null) payload.put("presence_penalty", presencePenalty);
            if (maxTokens != null) payload.put("max_tokens", maxTokens);
            if (minTokens != null) payload.put("min_tokens", minTokens);
            if (seed != null) payload.put("seed", seed);
            if (stop != null && !stop.isEmpty()) payload.put("stop", stop);

            return payload;
        }
        
        /**
         * Get a summary of non-default settings for display.
         */
        public String getSettingsSummary() {
            List<String> settings = new ArrayList<>();
            if (temperature != null) settings.add("temp=" + temperature);
            if (topP != null) settings.add("top_p=" + topP);
            if (topK != null) settings.add("top_k=" + topK);
            if (minP != null) settings.add("min_p=" + minP);
            if (repetitionPenalty != null) settings.add("rep_pen=" + repetitionPenalty);
            if (frequencyPenalty != null) settings.add("freq_pen=" + frequencyPenalty);
            if (presencePenalty != null) settings.add("pres_pen=" + presencePenalty);
            if (maxTokens != null) settings.add("max_tokens=" + maxTokens);
            if (minTokens != null) settings.add("min_tokens=" + minTokens);
            if (seed != null) settings.add("seed=" + seed);
            if (skipSystem != null && skipSystem) settings.add("no_system");
            if (stop != null && !stop.isEmpty()) settings.add("stop=" + String.join(",", stop));
            return String.join(", ", settings);
        }
    }

    public DebugAIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken, String arliApiKey) {
        this.client = client;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
    }

     /**
      * Parse debug parameters from command arguments.
      * Format: !debugarliai <model> [param=value ...] <prompt>
      *
      * Supported parameter names (case-insensitive):
      * - temp, temperature: temperature (0.0-2.0)
      * - top_p, topP: nucleus sampling (0-1)
      * - top_k, topK: top-k sampling
      * - min_p, minP: min-p sampling (0-1)
      * - rep_pen, repetition_penalty: repetition penalty (>=1)
      * - freq_pen, frequency_penalty: frequency penalty
      * - pres_pen, presence_penalty: presence penalty
      * - max_tokens, maxTokens: max tokens to generate
      * - min_tokens, minTokens: min tokens to generate
      * - seed: random seed for reproducibility
      * - no_system, nosystem: skip system prompt
      * - stop: comma-separated stop sequences
      */
    public static ParseResult parseArguments(String args) {
        DebugConfig config = new DebugConfig();
        List<String> remainingParts = new ArrayList<>();
        
        // Pattern to match parameter=value or parameter:value
        Pattern paramPattern = Pattern.compile("^([a-z_]+)[=:]([^\\s]+)$", Pattern.CASE_INSENSITIVE);
        
        String[] parts = args.trim().split("\\s+");
        boolean foundModel = false;
        String model = null;
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            
            // First non-parameter part is the model
            if (!foundModel && !isParameter(part)) {
                model = part;
                foundModel = true;
                continue;
            }
            
            // Check if this is a parameter
            Matcher matcher = paramPattern.matcher(part);
            if (matcher.matches()) {
                String paramName = matcher.group(1).toLowerCase();
                String paramValue = matcher.group(2);
                
                try {
                    switch (paramName) {
                        // Temperature
                        case "temp":
                        case "temperature":
                            config.temperature = Double.parseDouble(paramValue);
                            if (config.temperature < 0 || config.temperature > 2) {
                                return new ParseResult(null, null, "Temperature must be between 0.0 and 2.0");
                            }
                            break;
                            
                        // Top-p
                        case "top_p":
                        case "topp":
                            config.topP = Double.parseDouble(paramValue);
                            if (config.topP <= 0 || config.topP > 1) {
                                return new ParseResult(null, null, "top_p must be between 0 (exclusive) and 1 (inclusive)");
                            }
                            break;
                            
                        // Top-k
                        case "top_k":
                        case "topk":
                            config.topK = Integer.parseInt(paramValue);
                            break;
                            
                        // Min-p
                        case "min_p":
                        case "minp":
                            config.minP = Double.parseDouble(paramValue);
                            if (config.minP < 0 || config.minP > 1) {
                                return new ParseResult(null, null, "min_p must be between 0 and 1");
                            }
                            break;
                            

                            
                        // Repetition penalty
                        case "rep_pen":
                        case "repetition_penalty":
                            config.repetitionPenalty = Double.parseDouble(paramValue);
                            if (config.repetitionPenalty < 1) {
                                return new ParseResult(null, null, "repetition_penalty must be >= 1");
                            }
                            break;
                            
                        // Frequency penalty
                        case "freq_pen":
                        case "frequency_penalty":
                            config.frequencyPenalty = Double.parseDouble(paramValue);
                            break;
                            
                        // Presence penalty
                        case "pres_pen":
                        case "presence_penalty":
                            config.presencePenalty = Double.parseDouble(paramValue);
                            break;
                            

                            
                        // Max tokens
                        case "max_tokens":
                        case "maxtokens":
                            config.maxTokens = Integer.parseInt(paramValue);
                            break;
                            
                        // Min tokens
                        case "min_tokens":
                        case "mintokens":
                            config.minTokens = Integer.parseInt(paramValue);
                            break;
                            
                        // Seed
                        case "seed":
                            config.seed = Integer.parseInt(paramValue);
                            break;
                            
                        // Stop sequences
                        case "stop":
                            config.stop = Arrays.asList(paramValue.split(","));
                            break;
                            
                        // Skip system prompt
                        case "no_system":
                        case "nosystem":
                            config.skipSystem = true;
                            break;
                            
                        default:
                            // Unknown parameter - treat as part of prompt
                            remainingParts.add(part);
                            break;
                    }
                } catch (NumberFormatException e) {
                    return new ParseResult(null, null, "Invalid number format for parameter: " + paramName + "=" + paramValue);
                }
            } else {
                // Not a parameter - add to remaining parts (prompt)
                remainingParts.add(part);
            }
        }
        
        if (model == null || model.isEmpty()) {
            return new ParseResult(null, null, "Model name is required");
        }
        
        String prompt = String.join(" ", remainingParts);
        if (prompt.isEmpty()) {
            return new ParseResult(null, null, "Prompt is required");
        }
        
        config.model = model;
        return new ParseResult(config, prompt, null);
    }
    
    /**
     * Check if a string looks like a parameter (contains = or starts with known param names)
     */
    private static boolean isParameter(String s) {
        if (s.contains("=") || s.contains(":")) return true;
        String lower = s.toLowerCase();
        return lower.equals("no_system") || lower.equals("nosystem");
    }
    
    /**
     * Result of parsing command arguments.
     */
    public static class ParseResult {
        public final DebugConfig config;
        public final String prompt;
        public final String error;
        
        public ParseResult(DebugConfig config, String prompt, String error) {
            this.config = config;
            this.prompt = prompt;
            this.error = error;
        }
        
        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * Execute a debug AI query with the given configuration.
     */
    public void queryDebugAI(String responseRoomId, String exportRoomId, String prevBatch, 
                             DebugConfig config, String prompt, AtomicBoolean abortFlag,
                             RoomHistoryManager historyManager) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        
        try {
            // Resolve model with fuzzy matching
            String model = fuzzyMatchModel(config.model);
            if (model == null) {
                matrixClient.sendText(responseRoomId, "Unknown model: " + config.model + "\n" +
                        "Available models: " + String.join(", ", ARLI_MODELS) +
                        "\nModel names are fuzzy matched (e.g., 'musica', 'vivid', 'derestricted')");
                return;
            }
            
            // Send initial status message
            String settingsSummary = config.getSettingsSummary();
            String statusMsg = "⚙️ Debug AI Query (" + model + ")";
            if (!settingsSummary.isEmpty()) {
                statusMsg += "\nSettings: " + settingsSummary;
            }
            String statusEventId = matrixClient.sendNoticeWithEventId(responseRoomId, statusMsg);
            
            if (statusEventId == null) return;
            
            // Build messages
            List<Map<String, String>> messages = new ArrayList<>();
            if (!config.skipSystem) {
                messages.add(Map.of("role", "system", "content", AIService.Prompts.SYSTEM_OVERVIEW));
            }
            messages.add(Map.of("role", "user", "content", prompt));
            messages.add(Map.of("role", "assistant", "content", "<think></think>\n"));
            
            // Call Arli AI with custom parameters
            callArliAIDebug(responseRoomId, exportRoomId, model, messages, config, abortFlag, statusEventId, prevBatch, historyManager);
            
        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error in debug AI query: " + e.getMessage());
        }
    }

    /**
     * Call Arli AI API with debug configuration.
     */
    private void callArliAIDebug(String responseRoomId, String exportRoomId, String model,
                                  List<Map<String, String>> messages, DebugConfig config,
                                  AtomicBoolean abortFlag, String statusEventId,
                                  String prevBatch, RoomHistoryManager historyManager) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
        }

        Map<String, Object> payload = config.toPayloadMap(model, messages);
        String jsonPayload = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(AIService.AI_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        AtomicReference<String> eventIdObj = new AtomicReference<>(null);

        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        AtomicLong lastUpdate = new AtomicLong(System.currentTimeMillis());
        final long startTime = System.currentTimeMillis();

        AtomicInteger updateCount = new AtomicInteger(0);
        String[] clockFaces = {"🕛", "🕧", "🕐", "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕", "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚", "🕦"};

        try {
            System.out.println("Starting ArliAI debug streaming request...");
            HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            
            if (response.statusCode() != 200) {
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"));
                throw new Exception("Status: " + response.statusCode() + " Body: " + errorBody);
            }
            
            try (java.util.stream.Stream<String> lines = response.body()) {
                java.util.Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    if (abortFlag != null && abortFlag.get()) {
                        System.out.println("ArliAI debug streaming aborted by flag.");
                        break;
                    }
                    String line = it.next();
                    String data = line.trim();
                    if (data.isEmpty()) continue;
                    
                    if (data.startsWith("data:") && !data.contains("[DONE]")) {
                        try {
                            String json = data.substring(5).trim();
                            if (json.isEmpty()) continue;
                            
                            JsonNode node = mapper.readTree(json);
                            JsonNode choices = node.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).path("delta");
                                if (delta.has("content")) {
                                    content.append(delta.get("content").asText());
                                } else if (delta.has("reasoning")) {
                                    reasoning.append(delta.get("reasoning").asText());
                                } else if (delta.has("reasoning_content")) {
                                    reasoning.append(delta.get("reasoning_content").asText());
                                }
                                
                                long now = System.currentTimeMillis();
                                if ((content.length() > 0 || reasoning.length() > 0) && now - lastUpdate.get() > 5000) {
                                    lastUpdate.set(now);
                                    StringBuilder streamingOutput = new StringBuilder();
                                    if (reasoning.length() > 0) {
                                        String r = trimReasoning(reasoning.toString());
                                        streamingOutput.append("> ").append(r.replace("\n", "\n> ")).append("\n\n");
                                    }
                                    if (content.length() > 0) {
                                        streamingOutput.append(content.toString());
                                    }
                                    
                                    String output = streamingOutput.toString();
                                    if (output.length() > 16000) {
                                        output = output.substring(0, 15900) + "... [TRUNCATED]";
                                    }
                                    
                                    // Append elapsed thinking time to clock emoji (e.g. 🕒 1m12s)
                                    long elapsedMs = now - startTime;
                                    long elapsedSec = elapsedMs / 1000;
                                    String elapsedStr = elapsedSec < 60 ? (elapsedSec + "s") : ((elapsedSec / 60) + "m" + (elapsedSec % 60) + "s");
                                    String indicator = clockFaces[updateCount.getAndIncrement() % clockFaces.length] + " " + elapsedStr;
                                    if (eventIdObj.get() == null) {
                                        eventIdObj.set(matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, output + " " + indicator));
                                    } else {
                                        matrixClient.updateMarkdownNoticeMessage(responseRoomId, eventIdObj.get(), output + " " + indicator);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("ArliAI debug Stream Parse Error: " + e.getMessage() + " | Line: " + line);
                        }
                    } else if (data.contains("[DONE]")) {
                        System.out.println("ArliAI debug streaming finished normally ([DONE] received).");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error during ArliAI debug streaming call: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("Error during ArliAI debug streaming: " + e.getMessage(), e);
        }

        if (content.length() == 0 && reasoning.length() == 0) {
            throw new Exception("No response received from ArliAI debug streaming.");
        }

        System.out.println("ArliAI debug Final State - Content size: " + content.length() + ", Reasoning size: " + reasoning.length());
        
        String finalOutput;
        if (content.toString().trim().isEmpty()) {
            if (reasoning.length() > 0) {
                System.out.println("ArliAI debug: Content is empty, falling back to trimmed reasoning.");
                String trimmed = trimReasoning(reasoning.toString());
                finalOutput = "> " + trimmed.replace("\n", "\n> ") + "\n\n**ArliAI: No final response was generated.**";
            } else {
                finalOutput = "**ArliAI Error: No final response was generated.**";
            }
        } else {
            finalOutput = content.toString();
        }

        if (finalOutput.length() > 16000) {
            finalOutput = finalOutput.substring(0, 15900) + "... [TRUNCATED]";
        }

        String answer = finalOutput;
        if (eventIdObj.get() == null) {
            matrixClient.sendMarkdownWithEventId(responseRoomId, answer);
        } else {
            matrixClient.updateMarkdownMessage(responseRoomId, eventIdObj.get(), answer);
        }
    }

    /**
     * Fuzzy match a model name input to the list of available models.
     */
    private String fuzzyMatchModel(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        
        String lowerInput = input.toLowerCase();
        
        // First, try exact match (case-insensitive)
        for (String model : ARLI_MODELS) {
            if (model.equalsIgnoreCase(input)) {
                return model;
            }
        }
        
        // Then try contains match
        for (String model : ARLI_MODELS) {
            if (model.toLowerCase().contains(lowerInput)) {
                return model;
            }
        }
        
        // Try matching with underscores replaced by hyphens
        String normalizedInput = lowerInput.replace("_", "-");
        for (String model : ARLI_MODELS) {
            if (model.toLowerCase().replace("_", "-").contains(normalizedInput)) {
                return model;
            }
        }
        
        return null;
    }

    /**
     * Trim reasoning to reasonable length.
     */
    private String trimReasoning(String r) {
        if (r == null || r.isEmpty()) return "";
        String[] lines = r.split("\n");
        List<Integer> stepIndices = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("^(\\d+\\.|\\*).*")) {
                stepIndices.add(i);
            }
        }
        
        int lastStepIdx = -1;
        if (!stepIndices.isEmpty()) {
            lastStepIdx = stepIndices.get(stepIndices.size() - 1);
        }
        
        int maxLines = 15;
        int maxChars = 2500;
        
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        for (int i = lines.length - 1; i >= 0 && lineCount < maxLines; i--) {
            if (sb.length() + lines[i].length() + 1 > maxChars) break;
            
            sb.insert(0, lines[i] + "\n");
            lineCount++;
            
            if (i == lastStepIdx) break;
        }
        
        return sb.toString().trim();
    }

    /**
     * Get help text for the debug command.
     */
    public static String getHelpText() {
        return "**!debugarliai <model> [params...] <prompt>** - Query ArliAI with custom API parameters\n\n" +
               "Models: " + String.join(", ", ARLI_MODELS) + " (fuzzy matched)\n\n" +
               "**Parameters** (format: `name=value`):\n" +
               "• `temp` - Temperature (0.0-2.0, default ~0.7)\n" +
               "• `top_p` - Nucleus sampling (0-1)\n" +
               "• `top_k` - Top-k sampling\n" +
               "• `min_p` - Min-p sampling (0-1)\n" +
               "• `rep_pen` - Repetition penalty (>=1)\n" +
               "• `freq_pen` - Frequency penalty\n" +
               "• `pres_pen` - Presence penalty\n" +
               "• `max_tokens` - Max tokens to generate\n" +
               "• `min_tokens` - Min tokens to generate\n" +
               "• `seed` - Random seed for reproducibility\n" +
               "• `no_system` - Skip system prompt\n" +
               "• `stop` - Comma-separated stop sequences\n\n" +
               "**Examples:**\n" +
               "• `!debugarliai musica What is 2+2?`\n" +
               "• `!debugarliai derestricted temp=0.9 Tell me a story`\n" +
               "• `!debugarliai vivid temp=0.3 top_p=0.8 rep_pen=1.2 Explain quantum physics`\n" +
               "• `!debugarliai musica seed=42 max_tokens=500 Write a poem`";
    }
}
