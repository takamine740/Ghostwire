package com.ghostwire.features;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Optional;

/**
 * Reads one Repeater proposal from a model reply.
 * Accepts the ACTION / REASON / REQUEST shape, or one JSON object with the same fields.
 */
public final class ProposalParser {

    public record Proposal(String action, String reason, String request) {
        public boolean send() {
            return "send".equals(action);
        }

        public boolean done() {
            return "done".equals(action);
        }
    }

    private ProposalParser() {}

    public static Optional<Proposal> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = stripFences(text.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (cleaned.toUpperCase(Locale.ROOT).contains("ACTION:")) {
            Optional<Proposal> lines = parseLines(cleaned);
            if (lines.isPresent()) {
                return lines;
            }
        }
        return parseJson(cleaned);
    }

    private static Optional<Proposal> parseLines(String text) {
        String[] lines = text.split("\n", -1);
        String action = null;
        String reason = "";
        int requestLine = -1;
        String inline = "";
        for (int i = 0; i < lines.length && requestLine < 0; i++) {
            String line = lines[i].trim();
            if (action == null && starts(line, "ACTION:")) {
                action = actionOf(after(line, "ACTION:"));
            } else if (starts(line, "REASON:")) {
                reason = after(line, "REASON:");
            } else if (starts(line, "REQUEST:")) {
                requestLine = i;
                inline = after(line, "REQUEST:");
            }
        }
        if (!"send".equals(action) && !"done".equals(action)) {
            return Optional.empty();
        }
        if ("done".equals(action)) {
            return finish("done", reason, "");
        }
        StringBuilder request = new StringBuilder(inline);
        int start = requestLine + 1;
        if (inline.isEmpty()) {
            while (start < lines.length && lines[start].isBlank()) {
                start++;
            }
        }
        for (int i = start; i < lines.length; i++) {
            if (request.length() > 0) {
                request.append('\n');
            }
            request.append(lines[i]);
        }
        return finish("send", reason, request.toString());
    }

    private static Optional<Proposal> parseJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text.substring(start, end + 1));
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("action")) {
                return Optional.empty();
            }
            String action = actionOf(asString(obj.get("action")));
            if (!"send".equals(action) && !"done".equals(action)) {
                return Optional.empty();
            }
            String reason = obj.has("reason") ? asString(obj.get("reason")) : "";
            String request = obj.has("request") ? asString(obj.get("request")) : "";
            return finish(action, reason, request);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static Optional<Proposal> finish(String action, String reason, String request) {
        String why = reason == null || reason.isBlank() ? "No reason given." : reason.trim().replace('\n', ' ');
        if (why.length() > 300) {
            why = why.substring(0, 300);
        }
        if ("done".equals(action)) {
            return Optional.of(new Proposal("done", why, ""));
        }
        if (request == null || !looksLikeRequest(request)) {
            return Optional.empty();
        }
        return Optional.of(new Proposal("send", why, request.strip()));
    }

    private static boolean looksLikeRequest(String request) {
        String trimmed = request.strip();
        int newline = trimmed.indexOf('\n');
        String first = (newline < 0 ? trimmed : trimmed.substring(0, newline)).trim();
        return first.matches("[A-Za-z]{1,20}\\s+\\S+.*");
    }

    private static String actionOf(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        int cut = token.length();
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isLetter(token.charAt(i))) {
                cut = i;
                break;
            }
        }
        return token.substring(0, cut);
    }

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static boolean starts(String line, String prefix) {
        return line.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String after(String line, String prefix) {
        return line.substring(prefix.length()).trim();
    }

    private static String stripFences(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }
}
