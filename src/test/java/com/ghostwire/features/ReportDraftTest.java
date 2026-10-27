package com.ghostwire.features;

import com.ghostwire.config.Prompts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportDraftTest {

    @Test
    void parsesADowngradeAndRejectsProse() {
        TriageNotes.Note note = TriageNotes.parse("""
                VERDICT: downgrade
                SEVERITY: low
                NOTE: The response shows the header, and that is all.
                REPRO: The request was GET / and the response included the header.
                REMEDIATION: Confirm the header is intentional.
                """).orElseThrow();
        assertEquals("downgrade", note.verdict());
        assertEquals("low", note.severity());
        assertTrue(note.repro().contains("GET /"));
        assertTrue(TriageNotes.parse("This issue looks fine.").isEmpty());
    }

    @Test
    void rejectForcesInformationalSeverity() {
        TriageNotes.Note note = TriageNotes.parse("""
                VERDICT: reject
                SEVERITY: high
                NOTE: The exchange does not show the issue.
                """).orElseThrow();
        assertEquals("reject", note.verdict());
        assertEquals("info", note.severity());
        assertEquals("not shown", note.repro());
    }

    @Test
    void reportCountsVerdictsAndOnlyLowersARealDowngrade() {
        String report = ReportText.render(List.of(
                new ReportText.Item("Missing header", "https://www.example.com/", "HIGH", "downgrade", "low",
                        "The header is absent.", "GET / returned no header.", "Add the header if the page needs it."),
                new ReportText.Item("Other", "https://www.example.com/a", "LOW", "confirm", "low",
                        "Supported.", "Shown.", "Leave it."),
                new ReportText.Item("Noise", "https://www.example.com/b", "MEDIUM", "reject", "info",
                        "Not supported.", "not shown", "Leave it.")
        ));
        assertTrue(report.contains("Confirmed: 1"));
        assertTrue(report.contains("Downgraded: 1"));
        assertTrue(report.contains("Rejected: 1"));
        assertTrue(report.contains("1. Missing header"));
        assertTrue(ReportText.lowerSeverity("HIGH", "low", "downgrade"));
        assertFalse(ReportText.lowerSeverity("LOW", "low", "confirm"));
        assertFalse(ReportText.lowerSeverity("LOW", "high", "downgrade"));
    }

    @Test
    void plainTextDropsTags() {
        assertEquals("See the header.", IssueText.plain("<p>See the header.</p>"));
    }

    @Test
    void promptFillsIssueFields() {
        String text = TriageNotes.prompt("{{NAME}} {{SEVERITY}} {{URL}}", "Missing header", "HIGH", "FIRM",
                "https://www.example.com/", "detail", "GET /");
        assertEquals("Missing header HIGH https://www.example.com/", text);
    }

    @Test
    void bundledReportPromptHasTheVerdictShape() {
        String prompt = Prompts.bundled(Prompts.REPORT);
        assertTrue(prompt.contains("{{EXCHANGE}}"));
        assertTrue(prompt.contains("VERDICT:"));
        assertTrue(prompt.contains("downgrade"));
    }
}
