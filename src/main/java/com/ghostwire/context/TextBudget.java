package com.ghostwire.context;

import com.ghostwire.llm.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Header filtering, redaction, and truncation. No Burp types, so it can be tested on its own. */
public final class TextBudget {

    private static final Set<String> ALLOWED = Set.of(
            "host", "content-type", "content-length", "accept", "origin", "referer", "location", "content-disposition");

    private static final Set<String> AUTH = Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization", "api-key",
            "x-api-key", "x-auth-token", "x-access-token", "x-csrf-token", "x-xsrf-token");

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/=]{8,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)(access_token|id_token|refresh_token|api_key|apikey|password|passwd|secret)=([^&\\s]+)");

    private TextBudget() {
    }

    public static boolean keepHeader(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return ALLOWED.contains(normalized) || AUTH.contains(normalized) || normalized.startsWith("x-");
    }

    public static String formatHeader(String name, String value, boolean redact) {
        String shown;
        if (redact && AUTH.contains(name.toLowerCase(Locale.ROOT))) {
            shown = "<REDACTED:" + name + ">";
        } else {
            shown = redact ? redactSecrets(value) : value;
            shown = headAndTail(shown, 1500);
        }
        return name + ": " + shown;
    }

    public static String redactSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String withoutBearer = BEARER.matcher(text).replaceAll("<REDACTED:bearer>");
        String withoutJwt = JWT.matcher(withoutBearer).replaceAll("<REDACTED:jwt>");
        return QUERY_SECRET.matcher(withoutJwt).replaceAll("$1=<REDACTED>");
    }

    public static boolean binaryContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        if (type.startsWith("image/") && !type.contains("svg")) {
            return true;
        }
        if (type.startsWith("audio/") || type.startsWith("video/") || type.startsWith("font/")) {
            return true;
        }
        return type.equals("application/octet-stream")
                || type.equals("application/pdf")
                || type.equals("application/zip")
                || type.equals("application/gzip")
                || type.equals("application/wasm")
                || type.equals("application/x-protobuf")
                || type.equals("application/x-shockwave-flash")
                || type.contains("font-woff");
    }

    public static boolean binaryMimeName(String mimeName) {
        if (mimeName == null) {
            return false;
        }
        return switch (mimeName) {
            case "IMAGE_UNKNOWN", "IMAGE_JPEG", "IMAGE_GIF", "IMAGE_PNG", "IMAGE_BMP", "IMAGE_TIFF",
                 "SOUND", "VIDEO", "APPLICATION_FLASH", "APPLICATION_UNKNOWN",
                 "FONT_WOFF", "FONT_WOFF2", "LEGACY_SER_AMF" -> true;
            default -> false;
        };
    }

    /** Prefer the Content-Type header. Fall back to Burp's MIME name when the header is missing. */
    public static boolean isBinary(String contentType, String mimeName) {
        if (contentType != null && !contentType.isBlank()) {
            return binaryContentType(contentType);
        }
        return binaryMimeName(mimeName);
    }

    public static String headAndTail(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        int limit = Math.max(maxChars, 64);
        if (text.length() <= limit) {
            return text;
        }
        int keep = limit - 40;
        int head = (keep * 7) / 10;
        int tail = keep - head;
        int omitted = text.length() - head - tail;
        String marker = marker(omitted);
        while (head + tail + marker.length() > limit && tail > 0) {
            tail--;
            omitted++;
            marker = marker(omitted);
        }
        while (head + tail + marker.length() > limit && head > 0) {
            head--;
            omitted++;
            marker = marker(omitted);
        }
        return text.substring(0, head) + marker + text.substring(text.length() - tail);
    }

    /**
     * Keeps the newest turns that fit in {@code maxChars}. Older turns become a single note.
     * The result always starts with a user turn, which the Converse API requires.
     */
    public static List<ChatMessage> fitHistory(List<ChatMessage> history, int maxChars) {
        if (history.isEmpty()) {
            return List.of();
        }
        int budget = Math.max(maxChars, 64);
        ArrayList<ChatMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            String text = headAndTail(message.text(), budget);
            if (!kept.isEmpty() && used + text.length() > budget) {
                break;
            }
            kept.add(new ChatMessage(message.role(), text));
            used += text.length();
        }
        Collections.reverse(kept);
        int omitted = history.size() - kept.size();
        if (omitted == 0) {
            return List.copyOf(kept);
        }
        String note = "[Earlier conversation omitted: " + omitted + " turns]";
        if ("user".equals(kept.get(0).role())) {
            kept.set(0, new ChatMessage("user", note + "\n" + kept.get(0).text()));
        } else {
            kept.add(0, new ChatMessage("user", note));
        }
        return List.copyOf(kept);
    }

    private static String marker(int omitted) {
        return "\n...[truncated " + omitted + " characters]...\n";
    }
}
