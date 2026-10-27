package com.ghostwire.features;

import java.util.Locale;
import java.util.Optional;

/** One triage reply: verdict, recommended severity, and the report sentences. */
public final class TriageNotes {

    public record Note(String verdict, String severity, String note, String repro, String remediation) {}

    private TriageNotes() {}

    public static String prompt(String template, String name, String severity, String confidence,
                                String url, String detail, String exchange) {
        String body = template == null ? "" : template;
        if (body.contains("{{NAME}}") || body.contains("{{EXCHANGE}}") || body.contains("{{DETAIL}}")) {
            return body.replace("{{NAME}}", blank(name))
                    .replace("{{SEVERITY}}", blank(severity))
                    .replace("{{CONFIDENCE}}", blank(confidence))
                    .replace("{{URL}}", blank(url))
                    .replace("{{DETAIL}}", blank(detail))
                    .replace("{{EXCHANGE}}", blank(exchange));
        }
        return body + "\n\nIssue: " + name + "\nSeverity: " + severity + "\nURL: " + url
                + "\n\nDetail:\n" + detail + "\n\nExchange:\n" + exchange;
    }

    public static Optional<Note> parse(String text) {
        if (text == null || text.isBlank() || !text.toUpperCase(Locale.ROOT).contains("VERDICT:")) {
            return Optional.empty();
        }
        String verdict = "";
        String severity = "";
        String note = "";
        String repro = "";
        String remediation = "";
        String field = null;
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String next = fieldOf(line);
            if (next != null) {
                field = next;
                String value = after(line);
                switch (next) {
                    case "verdict" -> verdict = value;
                    case "severity" -> severity = value;
                    case "note" -> note = value;
                    case "repro" -> repro = value;
                    case "remediation" -> remediation = value;
                    default -> {
                    }
                }
                continue;
            }
            if (field == null) {
                continue;
            }
            switch (field) {
                case "verdict" -> verdict = join(verdict, line);
                case "severity" -> severity = join(severity, line);
                case "note" -> note = join(note, line);
                case "repro" -> repro = join(repro, line);
                case "remediation" -> remediation = join(remediation, line);
                default -> {
                }
            }
        }
        String decision = verdictOf(verdict);
        if (decision.isEmpty()) {
            return Optional.empty();
        }
        String recommended = "reject".equals(decision) ? "info" : severityOf(severity);
        String why = clip(note, 400);
        if (why.isBlank()) {
            why = "No explanation was given.";
        }
        String steps = clip(repro, 800);
        if (steps.isBlank()) {
            steps = "not shown";
        }
        String fix = clip(remediation, 500);
        if (fix.isBlank()) {
            fix = "Review the cited exchange before changing the issue.";
        }
        return Optional.of(new Note(decision, recommended, why, steps, fix));
    }

    public static int rank(String severity) {
        return switch (canonical(severity)) {
            case "low" -> 1;
            case "medium" -> 2;
            case "high" -> 3;
            default -> 0;
        };
    }

    public static String canonical(String severity) {
        if (severity == null) {
            return "info";
        }
        String value = severity.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("high") || value.startsWith("crit")) {
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

    private static String verdictOf(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("confirm")) {
            return "confirm";
        }
        if (value.startsWith("down")) {
            return "downgrade";
        }
        if (value.startsWith("reject") || value.startsWith("false")) {
            return "reject";
        }
        return "";
    }

    private static String severityOf(String raw) {
        String value = canonical(raw);
        return "info".equals(value) && raw != null && raw.toLowerCase(Locale.ROOT).startsWith("crit") ? "high" : value;
    }

    private static String fieldOf(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return switch (line.substring(0, colon).trim().toLowerCase(Locale.ROOT)) {
            case "verdict", "severity", "note", "repro", "remediation" -> line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
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

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
