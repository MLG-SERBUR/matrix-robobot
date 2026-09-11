package com.robomwm.ai.matrixrobobot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies downtime reports from @autoplayerbot:matrix.org in the export room.
 * <p>
 * The reporter only sends a service name (e.g. {@code [0-5-mikuplushfarm] [🔴 Down]}),
 * so we keep our own mapping of service name to health-check URL and ignore
 * anything unmapped. On a Down report for a mapped service, we probe the health
 * URL and react ✅ if the outage is confirmed (actually down) or ❌ if the
 * service is actually up (false alarm).
 */
public class DowntimeVerificationService {
    public static final String MONITORED_SENDER = "@autoplayerbot:matrix.org";
    public static final String CONFIRMED_DOWN_REACTION = "✅";
    public static final String REFUTED_DOWN_REACTION = "❌";

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);

    // Matches a status bracket containing "down" (case-insensitive), e.g. "[🔴 Down]".
    private static final Pattern DOWN_STATUS_PATTERN =
            Pattern.compile("\\[[^\\]]*down[^\\]]*\\]", Pattern.CASE_INSENSITIVE);
    // First bracketed token holds the service name, e.g. "[0-5-mikuplushfarm]".
    private static final Pattern SERVICE_TOKEN_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]");
    // Strips numeric monitor prefixes like "0-5-" from "0-5-mikuplushfarm".
    private static final Pattern NUMERIC_PREFIX_PATTERN = Pattern.compile("^(?:\\d+-)+");

    private final MatrixClient matrixClient;
    private final HttpClient httpClient;
    private final Map<String, String> serviceHealthUrls;

    public DowntimeVerificationService(MatrixClient matrixClient) {
        this(matrixClient, HttpClient.newBuilder()
                .connectTimeout(CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
                defaultServiceHealthUrls());
    }

    public DowntimeVerificationService(MatrixClient matrixClient, HttpClient httpClient) {
        this(matrixClient, httpClient, defaultServiceHealthUrls());
    }

    DowntimeVerificationService(MatrixClient matrixClient, HttpClient httpClient,
            Map<String, String> serviceHealthUrls) {
        this.matrixClient = matrixClient;
        this.httpClient = httpClient;
        Map<String, String> normalized = new HashMap<>();
        serviceHealthUrls.forEach((k, v) -> normalized.put(normalizeKey(k), v));
        this.serviceHealthUrls = Collections.unmodifiableMap(normalized);
    }

    private static Map<String, String> defaultServiceHealthUrls() {
        Map<String, String> urls = new HashMap<>();
        // mikuplushfarm is a Matrix homeserver at mikuplushfarm.ovh;
        // /_matrix/client/versions is public, unauthenticated, 200 when up.
        urls.put("mikuplushfarm", "https://mikuplushfarm.ovh/_matrix/client/versions");
        return urls;
    }

    private static String normalizeKey(String name) {
        return name == null ? null : name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Extract the mapped service key from a downtime report body.
     * Returns null when the body is not a Down report.
     */
    static String extractServiceName(String body) {
        if (body == null) {
            return null;
        }
        if (!DOWN_STATUS_PATTERN.matcher(body).find()) {
            return null;
        }
        Matcher m = SERVICE_TOKEN_PATTERN.matcher(body);
        if (!m.find()) {
            return null;
        }
        String raw = normalizeKey(m.group(1));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // Strip numeric monitor prefix: "0-5-mikuplushfarm" -> "mikuplushfarm".
        return NUMERIC_PREFIX_PATTERN.matcher(raw).replaceFirst("");
    }

    String resolveHealthUrl(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        return serviceHealthUrls.get(normalizeKey(serviceName));
    }

    /**
     * Probe a health URL. 2xx-3xx counts as up, anything else (network error,
     * timeout, 4xx/5xx) counts as down.
     */
    boolean isServiceUp(String healthUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .header("User-Agent", "matrix-robobot-downtime-check/1.0")
                    .timeout(CHECK_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 400;
        } catch (Exception e) {
            System.out.println("Downtime check failed for " + healthUrl + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Handle a timeline message. Only acts on Down reports from the monitored
     * sender in the export room for mapped services. Verification runs on a
     * background thread so the sync loop is never blocked.
     */
    public void processMessage(String roomId, String eventId, String body, String senderId,
            String exportRoomId) {
        if (body == null || senderId == null || roomId == null || eventId == null) {
            return;
        }
        if (!MONITORED_SENDER.equals(senderId)) {
            return;
        }
        if (exportRoomId == null || !exportRoomId.equals(roomId)) {
            return;
        }
        String serviceName = extractServiceName(body);
        if (serviceName == null) {
            return;
        }
        String healthUrl = resolveHealthUrl(serviceName);
        if (healthUrl == null) {
            return;
        }
        new Thread(() -> {
            boolean up = isServiceUp(healthUrl);
            // Report claims down: ✅ = confirmed down, ❌ = actually up.
            String reaction = up ? REFUTED_DOWN_REACTION : CONFIRMED_DOWN_REACTION;
            System.out.println("Downtime report for " + serviceName + " (up=" + up + ") -> " + reaction);
            matrixClient.sendReaction(roomId, eventId, reaction);
        }).start();
    }
}
