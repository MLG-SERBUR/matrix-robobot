package com.robomwm.ai.matrixrobobot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies downtime reports from @autoplayerbot:matrix.org in the export room.
 * <p>
 * The reporter includes the FQDN it pings (e.g. {@code mikuplushfarm.ovh}).
 * Reports without an FQDN (e.g. ICMP pings to a raw IP) are ignored.
 * On a Down report containing an FQDN, we probe {@code https://&lt;fqdn&gt;}
 * and react ✅ if the outage is confirmed (actually down) or ❌ if the
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
    // First bracketed token holds the service label, e.g. "[0-5-mikuplushfarm]".
    private static final Pattern SERVICE_TOKEN_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]");
    // Strips numeric monitor prefixes like "0-5-" from "0-5-mikuplushfarm".
    private static final Pattern NUMERIC_PREFIX_PATTERN = Pattern.compile("^(?:\\d+-)+");
    // FQDN anywhere in the body, e.g. "mikuplushfarm.ovh". Requires an alpha
    // TLD so raw IPv4 addresses (ICMP targets) never match.
    private static final Pattern FQDN_PATTERN = Pattern.compile(
            "\\b((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,})\\b",
            Pattern.CASE_INSENSITIVE);

    private final MatrixClient matrixClient;
    private final HttpClient httpClient;

    public DowntimeVerificationService(MatrixClient matrixClient) {
        this(matrixClient, HttpClient.newBuilder()
                .connectTimeout(CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public DowntimeVerificationService(MatrixClient matrixClient, HttpClient httpClient) {
        this.matrixClient = matrixClient;
        this.httpClient = httpClient;
    }

    /**
     * Extract the service label from a downtime report body.
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

    private static String normalizeKey(String name) {
        return name == null ? null : name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Extract the first FQDN from the body. Returns null when none found.
     * Raw IPs never match (TLD must be alpha), so ICMP targets are ignored.
     */
    static String extractFqdn(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = FQDN_PATTERN.matcher(body);
        if (!m.find()) {
            return null;
        }
        return m.group(1).toLowerCase(Locale.ROOT);
    }

    static String buildHealthUrl(String fqdn) {
        if (fqdn == null) {
            return null;
        }
        return "https://" + fqdn;
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
     * sender in the export room that contain an FQDN. Verification runs on a
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
        String fqdn = extractFqdn(body);
        if (fqdn == null) {
            return;
        }
        String healthUrl = buildHealthUrl(fqdn);
        new Thread(() -> {
            boolean up = isServiceUp(healthUrl);
            // Report claims down: ✅ = confirmed down, ❌ = actually up.
            String reaction = up ? REFUTED_DOWN_REACTION : CONFIRMED_DOWN_REACTION;
            System.out.println("Downtime report for " + serviceName + " (" + fqdn + ", up=" + up + ") -> " + reaction);
            matrixClient.sendReaction(roomId, eventId, reaction);
        }).start();
    }
}
