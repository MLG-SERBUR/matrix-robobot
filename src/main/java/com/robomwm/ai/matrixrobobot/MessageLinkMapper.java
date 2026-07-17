package com.robomwm.ai.matrixrobobot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageLinkMapper {

    private record LogEntry(String timestamp, String sender, String body, String eventId) {}

    private record TextMatch(int start, int end) {}

    private static final Pattern LOG_LINE_PATTERN =
            Pattern.compile("^\\[(.+?)\\] <(.+?)> (.*)$");

    private static final Pattern TIMESTAMP_INLINE =
            Pattern.compile("\\[(\\d{1,2}:\\d{2})\\]");

    public static String mapLinks(String response, List<String> logs,
                                  List<String> eventIds, String exportRoomId) {
        if (response == null || response.isEmpty() ||
            logs == null || eventIds == null || logs.isEmpty() ||
            logs.size() != eventIds.size()) {
            return response;
        }

        List<LogEntry> entries = parseLogs(logs, eventIds);
        if (entries.isEmpty()) return response;

        List<Edit> edits = new ArrayList<>();

        for (LogEntry entry : entries) {
            String url = "https://matrix.to/#/" + exportRoomId + "/" + entry.eventId;

            // Body match → append " url" after matched text
            TextMatch bodyMatch = findBodyFuzzy(response, entry.body);
            if (bodyMatch != null) {
                edits.add(new Edit(bodyMatch.end(), -1, " " + url));
            }

            // Timestamp match → wrap in markdown link
            TextMatch tsMatch = findTimestampInResponse(response, entry.timestamp);
            if (tsMatch != null) {
                boolean nearBody = false;
                if (bodyMatch != null && Math.abs(tsMatch.start() - bodyMatch.start()) < 5) {
                    nearBody = true;
                }
                if (!nearBody) {
                    String originalTs = response.substring(tsMatch.start(), tsMatch.end());
                    edits.add(new Edit(tsMatch.start(), tsMatch.end(),
                            "[" + originalTs + "](" + url + ")"));
                }
            }
        }

        if (edits.isEmpty()) return response;

        edits.sort((a, b) -> Integer.compare(b.start, a.start));

        StringBuilder sb = new StringBuilder(response);
        for (Edit e : edits) {
            if (e.end >= 0) {
                sb.replace(e.start, e.end, e.replacement);
            } else {
                if (e.start <= sb.length()) {
                    sb.insert(e.start, e.replacement);
                }
            }
        }
        return sb.toString();
    }

    private static List<LogEntry> parseLogs(List<String> logs, List<String> eventIds) {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < logs.size(); i++) {
            String log = logs.get(i);
            Matcher m = LOG_LINE_PATTERN.matcher(log);
            if (m.matches()) {
                String eid = (eventIds != null && i < eventIds.size()) ? eventIds.get(i) : null;
                if (eid != null) {
                    entries.add(new LogEntry(m.group(1), m.group(2), m.group(3), eid));
                }
            }
        }
        return entries;
    }

    private static TextMatch findBodyFuzzy(String response, String body) {
        if (body == null || body.isEmpty()) return null;

        String normResp = normalize(response);
        String normBody = normalize(body);

        // Exact normalized match
        int idx = normResp.indexOf(normBody);
        if (idx >= 0) {
            return new TextMatch(idx, idx + normBody.length());
        }

        // Long body: try matching first 50 chars
        if (normBody.length() > 50) {
            String prefix = normBody.substring(0, 50);
            idx = normResp.indexOf(prefix);
            if (idx >= 0) return new TextMatch(idx, idx + prefix.length());
        }

        // Long body: try matching last 50 chars
        if (normBody.length() > 50) {
            String suffix = normBody.substring(normBody.length() - 50);
            idx = normResp.indexOf(suffix);
            if (idx >= 0) return new TextMatch(idx, idx + suffix.length());
        }

        return null;
    }

    private static TextMatch findTimestampInResponse(String response, String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return null;

        boolean hasDate = timestamp.contains("-");

        // Try bracketed match: [14:30] or [2024-01-15 14:30]
        String bracketed = "[" + timestamp + "]";
        int idx = response.indexOf(bracketed);
        if (idx >= 0) {
            return new TextMatch(idx, idx + bracketed.length());
        }

        // For date+time like "2024-01-15 14:30", try without brackets
        if (hasDate) {
            idx = response.indexOf(timestamp);
            if (idx >= 0) {
                return new TextMatch(idx, idx + timestamp.length());
            }
        } else {
            // Time-only: only match if surrounded by non-alphanumeric
            // to avoid matching things like "code14:30more"
            Pattern p = Pattern.compile("(^|[^\\w])" + Pattern.quote(timestamp) + "($|[^\\w])");
            Matcher m = p.matcher(response);
            if (m.find()) {
                int start = m.start(1) + 1;
                return new TextMatch(start, start + timestamp.length());
            }
        }

        return null;
    }

    private static String normalize(String s) {
        return s.toLowerCase().trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[”“]", "\"")
                .replaceAll("[‘’]", "'");
    }

    private record Edit(int start, int end, String replacement) {}

    // Package-private for testing
    static String normalizeForTest(String s) {
        return normalize(s);
    }
}
