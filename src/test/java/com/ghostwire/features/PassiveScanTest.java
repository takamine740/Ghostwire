package com.ghostwire.features;

import com.ghostwire.config.Prompts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassiveScanTest {

    @Test
    void noneAndProseProduceNoIssues() {
        assertTrue(IssueNotes.parse("NONE").isEmpty());
        assertTrue(IssueNotes.parse("The page looks fine.").isEmpty());
    }

    @Test
    void parsesOneIssueAndCapsTheList() {
        String reply = """
                ISSUE: Missing a header the response itself describes
                SEVERITY: low
                CONFIDENCE: firm
                DETAIL: The response HTML names a script at /static/app.js and that path is visible in the body.
                REMEDIATION: Confirm the script reference and serve it only if the page needs it.

                ISSUE: Second
                SEVERITY: high
                CONFIDENCE: certain
                DETAIL: Second detail is long enough.
                REMEDIATION: Second fix.

                ISSUE: Third
                SEVERITY: info
                CONFIDENCE: tentative
                DETAIL: Third detail is long enough.
                REMEDIATION: Third fix.

                ISSUE: Fourth
                SEVERITY: medium
                CONFIDENCE: firm
                DETAIL: Fourth detail should be dropped.
                REMEDIATION: Fourth fix.
                """;
        List<IssueNotes.Note> notes = IssueNotes.parse(reply);
        assertEquals(3, notes.size());
        assertEquals("Missing a header the response itself describes", notes.get(0).name());
        assertEquals("low", notes.get(0).severity());
        assertEquals("firm", notes.get(0).confidence());
        assertEquals("high", notes.get(1).severity());
        assertEquals("certain", notes.get(1).confidence());
    }

    @Test
    void unknownSeverityStaysInformational() {
        IssueNotes.Note note = IssueNotes.parse("""
                ISSUE: Ghostwire: Odd label
                SEVERITY: critical
                CONFIDENCE: maybe
                DETAIL: The body contains the word example.
                """).get(0);
        assertEquals("Odd label", note.name());
        assertEquals("info", note.severity());
        assertEquals("tentative", note.confidence());
        assertTrue(note.remediation().contains("confirm"));
    }

    @Test
    void textResponsesPassAndBinaryResponsesDoNot() {
        assertTrue(ScanFilter.textResponse("text/html; charset=utf-8", "HTML"));
        assertTrue(ScanFilter.textResponse(null, "JSON"));
        assertTrue(ScanFilter.textResponse("application/problem+json", null));
        assertFalse(ScanFilter.textResponse("image/png", "IMAGE_PNG"));
        assertFalse(ScanFilter.textResponse("application/octet-stream", null));
        assertFalse(ScanFilter.textResponse(null, "UNRECOGNIZED"));
    }

    @Test
    void responseHashChangesWhenTheBodyOrUrlChanges() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String first = ResponseHash.key("https://www.example.com/", 200, body);
        assertEquals(first, ResponseHash.key("https://www.example.com/", 200, body));
        assertNotEquals(first, ResponseHash.key("https://www.example.com/other", 200, body));
        assertNotEquals(first, ResponseHash.key("https://www.example.com/", 200, "hello!".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledPassivePromptAsksForEvidence() {
        String prompt = Prompts.bundled(Prompts.PASSIVE);
        assertTrue(prompt.contains("{{EXCHANGE}}"));
        assertTrue(prompt.contains("NONE"));
        assertTrue(prompt.contains("ISSUE:"));
    }

    @Test
    void promptAppendsTheExchangeWhenThePlaceholderIsMissing() {
        String text = IssueNotes.prompt("Look only at the exchange.", "GET /");
        assertTrue(text.contains("GET /"));
    }
}
