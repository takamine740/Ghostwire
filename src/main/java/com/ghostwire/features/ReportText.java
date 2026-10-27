package com.ghostwire.features;

import java.util.List;

/** The triage report assembled from one note per scanner issue. */
public final class ReportText {

    public static final int MAX_ISSUES = 15;

    public record Item(String name, String url, String scannerSeverity, String verdict, String severity,
                       String note, String repro, String remediation) {}

    private ReportText() {}

    public static String render(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return "No issues were drafted.";
        }
        int confirmed = 0;
        int downgraded = 0;
        int rejected = 0;
        for (Item item : items) {
            switch (item.verdict() == null ? "" : item.verdict()) {
                case "confirm" -> confirmed++;
                case "downgrade" -> downgraded++;
                default -> rejected++;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Ghostwire triage report\n\n");
        out.append("Issues reviewed: ").append(items.size()).append('\n');
        out.append("Confirmed: ").append(confirmed).append('\n');
        out.append("Downgraded: ").append(downgraded).append('\n');
        out.append("Rejected: ").append(rejected).append("\n\n");
        int number = 1;
        for (Item item : items) {
            out.append(number++).append(". ").append(blank(item.name())).append('\n');
            out.append("URL: ").append(blank(item.url())).append('\n');
            out.append("Scanner severity: ").append(blank(item.scannerSeverity())).append('\n');
            out.append("Recommended severity: ").append(blank(item.severity())).append('\n');
            out.append("Verdict: ").append(blank(item.verdict())).append('\n');
            out.append("Note: ").append(blank(item.note())).append('\n');
            out.append("Reproduction: ").append(blank(item.repro())).append('\n');
            out.append("Remediation: ").append(blank(item.remediation())).append("\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    public static boolean lowerSeverity(String scannerSeverity, String recommended, String verdict) {
        return "downgrade".equals(verdict) && TriageNotes.rank(recommended) < TriageNotes.rank(scannerSeverity);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
