package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaseoDaemonClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Image(String base64, String mimeType) {}

    private final List<String> bridgeCommand;
    private final String provider;
    private final String cwd;

    public PaseoDaemonClient(List<String> bridgeCommand, String provider, String cwd) {
        if (bridgeCommand == null || bridgeCommand.isEmpty()) {
            throw new IllegalArgumentException("bridgeCommand must not be empty");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        this.bridgeCommand = List.copyOf(bridgeCommand);
        this.provider = provider;
        this.cwd = cwd;
    }

    public String provider() {
        return provider;
    }

    public String query(String prompt, String systemPrompt, List<Image> images, int timeoutSeconds,
            AtomicBoolean abortFlag) throws IOException, InterruptedException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("provider", provider);
        request.put("prompt", prompt);
        request.put("cwd", cwd);
        request.put("timeoutMs", TimeUnit.SECONDS.toMillis(timeoutSeconds));
        if (systemPrompt != null) request.put("systemPrompt", systemPrompt);
        ArrayNode imageNodes = request.putArray("images");
        for (Image image : images) {
            ObjectNode node = imageNodes.addObject();
            node.put("data", image.base64());
            node.put("mimeType", image.mimeType());
        }

        Process process = new ProcessBuilder(bridgeCommand).start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            try (var input = process.getOutputStream()) {
                input.write(MAPPER.writeValueAsBytes(request));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (abortFlag != null && abortFlag.get()) {
                    process.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("Paseo query aborted");
                }
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    throw new IOException("Paseo bridge timed out after " + timeoutSeconds + " seconds");
                }
            }
            String output = stdout.join();
            String error = stderr.join();
            if (process.exitValue() != 0) {
                throw new IOException("Paseo bridge exited " + process.exitValue() + ": " + truncate(error, 500));
            }
            JsonNode result = MAPPER.readTree(output);
            String answer = result.path("answer").asText(null);
            if (answer == null || answer.isBlank()) {
                throw new IOException("Paseo bridge returned no answer");
            }
            return answer;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } catch (CompletionException e) {
            process.destroyForcibly();
            throw new IOException("Failed to read Paseo bridge output", e.getCause());
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private static CompletableFuture<String> readAsync(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
