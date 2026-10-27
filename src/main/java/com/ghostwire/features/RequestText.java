package com.ghostwire.features;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a model-written request into HTTP/1.1 bytes Burp can parse.
 * The Host header is set here so the caller does not have to call {@code withHeader},
 * which throws inside Burp 2026.9 when the message uses bare line feeds.
 */
public final class RequestText {

    private RequestText() {}

    public static String pinHost(String raw, String hostValue) {
        if (raw == null || raw.isBlank() || hostValue == null || hostValue.isBlank()) {
            return null;
        }
        if (hostValue.indexOf('\r') >= 0 || hostValue.indexOf('\n') >= 0) {
            return null;
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        int split = normalized.indexOf("\n\n");
        String head = split < 0 ? normalized : normalized.substring(0, split);
        String body = split < 0 ? "" : normalized.substring(split + 2);
        String[] lines = head.split("\n", -1);
        String requestLine = requestLine(lines[0]);
        if (requestLine == null) {
            return null;
        }
        List<String> headers = new ArrayList<>();
        boolean sawHost = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.indexOf(':') < 0) {
                continue;
            }
            if (line.regionMatches(true, 0, "host", 0, 4) && line.substring(4).stripLeading().startsWith(":")) {
                if (!sawHost) {
                    headers.add("Host: " + hostValue);
                    sawHost = true;
                }
                continue;
            }
            headers.add(line);
        }
        if (!sawHost) {
            headers.add(0, "Host: " + hostValue);
        }
        StringBuilder out = new StringBuilder();
        out.append(requestLine).append("\r\n");
        for (String header : headers) {
            out.append(header).append("\r\n");
        }
        out.append("\r\n");
        out.append(body);
        return out.toString();
    }

    private static String requestLine(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || !parts[0].matches("[A-Za-z]{1,20}")) {
            return null;
        }
        String target = originTarget(parts[1]);
        if (target == null) {
            return null;
        }
        String version = "HTTP/1.1";
        if (parts.length >= 3 && parts[2].matches("(?i)HTTP/1\\.[01]")) {
            version = parts[2].toUpperCase(Locale.ROOT);
        }
        return parts[0].toUpperCase(Locale.ROOT) + " " + target + " " + version;
    }

    private static String originTarget(String target) {
        if (target.equals("*") || target.startsWith("/")) {
            return target;
        }
        if (!target.regionMatches(true, 0, "http://", 0, 7) && !target.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        try {
            URI uri = URI.create(target);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = uri.getRawQuery();
            return query == null ? path : path + "?" + query;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
