package com.ghostwire.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses a passive-scan reply into at most three evidence notes. */
public final class IssueNotes {

    public static final int MAX_ISSUES = 3;

    public record Note(String name, String severity, String confidence, String detail, String remediation) {}

    private IssueNotes() {}

    public static String prompt(String template, String exchange) {
        String body = template == null ? "" : template;
        if (body.contains("{{EXCHANGE}}")) {
            return body.replace("{{EXCHANGE}}", exchange == null ? "" : exchange);
        }
        return body + "\n\nExchange:\n" + exchange;
    }

    public static List<Note> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (!cleaned.toUpperCase(Locale.ROOT).contains("ISSUE:")) {
            return List.of();
        }
        List<Note> notes = new ArrayList<>();
        String name = null;
        String severity = "";
        String confidence = "";
        String detail = "";
        String remediation = "";
        String field = null;
        for (String rawLine : cleaned.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String next = fieldOf(line);
            if (next != null) {
                if ("issue".equals(next) && name != null) {
                    add(notes, name, severity, confidence, detail, remediation);
                    severity = "";
                    confidence = "";
                    detail = "";
                    remediation = "";
                }
                field = next;
                String value = after(line);
                if ("issue".equals(next)) {
                    name = value;
                } else {
                    switch (next) {
                        case "severity" -> severity = value;
                        case "confidence" -> confidence = value;
                        case "detail" -> detail = value;
                        case "remediation" -> remediation = value;
                        default -> {
                        }
                    }
                }
                continue;
            }
            if (field == null || "issue".equals(field)) {
                continue;
            }
            String appended = switch (field) {
                case "severity" -> join(severity, line);
                case "confidence" -> join(confidence, line);
                case "detail" -> join(detail, line);
                case "remediation" -> join(remediation, line);
                default -> "";
            };
            switch (field) {
                case "severity" -> severity = appended;
                case "confidence" -> confidence = appended;
                case "detail" -> detail = appended;
                case "remediation" -> remediation = appended;
                default -> {
                }
            }
        }
        if (name != null) {
            add(notes, name, severity, confidence, detail, remediation);
        }
        if (notes.size() > MAX_ISSUES) {
            return List.copyOf(notes.subList(0, MAX_ISSUES));
        }
        return List.copyOf(notes);
    }

    private static void add(List<Note> notes, String name, String severity, String confidence, String detail, String remediation) {
        String title = clip(stripPrefix(name), 80);
        String evidence = clip(detail, 500);
        if (title.isBlank() || evidence.isBlank() || title.equalsIgnoreCase("none")) {
            return;
        }
        String fix = clip(remediation, 300);
        if (fix.isBlank()) {
            fix = "Review the cited response and confirm the observation.";
        }
        notes.add(new Note(title, severityOf(severity), confidenceOf(confidence), evidence, fix));
    }

    private static String fieldOf(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "issue", "severity", "confidence", "detail", "remediation" -> name;
            default -> null;
        };
    }

    private static String after(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    private static String join(String current, String more) {
        if (current == null || current.isBlank()) {
            return more;
        }
        return current + " " + more;
    }

    private static String stripPrefix(String name) {
        String title = name == null ? "" : name.trim();
        if (title.regionMatches(true, 0, "ghostwire:", 0, "ghostwire:".length())) {
            title = title.substring("ghostwire:".length()).trim();
        }
        return title;
    }

    private static String severityOf(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("high")) {
            return "high";
        }
        if (value.startsWith("med")) {
            return "medium";
        }
        if (value.startsWith("low")) {
            return "low";
        }
        return "info";
    }

    private static String confidenceOf(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("certain")) {
            return "certain";
        }
        if (value.startsWith("firm")) {
            return "firm";
        }
        return "tentative";
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
