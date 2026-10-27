package com.ghostwire.context;

import com.ghostwire.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextBudgetTest {

    @Test
    void keepsUsefulHeadersAndDropsNoise() {
        assertTrue(TextBudget.keepHeader("Host"));
        assertTrue(TextBudget.keepHeader("Content-Type"));
        assertTrue(TextBudget.keepHeader("Cookie"));
        assertTrue(TextBudget.keepHeader("X-Request-Id"));
        assertFalse(TextBudget.keepHeader("User-Agent"));
        assertFalse(TextBudget.keepHeader("Accept-Encoding"));
    }

    @Test
    void redactsAuthHeadersAndLeavesOrdinaryOnes() {
        assertEquals("Authorization: <REDACTED:Authorization>",
                TextBudget.formatHeader("Authorization", "Bearer abcdefghijklmnop", true));
        assertEquals("Host: example.com", TextBudget.formatHeader("Host", "example.com", true));
        assertEquals("Authorization: Bearer abcdefghijklmnop",
                TextBudget.formatHeader("Authorization", "Bearer abcdefghijklmnop", false));
    }

    @Test
    void redactsBearerJwtAndQuerySecrets() {
        String jwt = "eyJaaaaaaaa.bbbbbbbb.cccccccc";
        String out = TextBudget.redactSecrets("Bearer abcdefghijklmnop " + jwt + " access_token=supersecretvalue&x=1");
        assertFalse(out.contains("abcdefghijklmnop"));
        assertFalse(out.contains(jwt));
        assertFalse(out.contains("supersecretvalue"));
        assertTrue(out.contains("<REDACTED:bearer>"));
        assertTrue(out.contains("<REDACTED:jwt>"));
        assertTrue(out.contains("access_token=<REDACTED>"));
        assertTrue(out.contains("x=1"));
    }

    @Test
    void detectsBinaryContentTypes() {
        assertTrue(TextBudget.binaryContentType("image/png"));
        assertTrue(TextBudget.binaryContentType("application/pdf"));
        assertFalse(TextBudget.binaryContentType("image/svg+xml"));
        assertFalse(TextBudget.binaryContentType("application/json; charset=utf-8"));
        assertTrue(TextBudget.isBinary(null, "IMAGE_PNG"));
        assertFalse(TextBudget.isBinary("application/json", "APPLICATION_UNKNOWN"));
    }

    @Test
    void truncatesWithHeadTailAndMarker() {
        assertEquals("short", TextBudget.headAndTail("short", 200));
        String text = "H".repeat(50) + "M".repeat(500) + "T".repeat(50);
        String out = TextBudget.headAndTail(text, 200);
        assertTrue(out.length() <= 200);
        assertTrue(out.startsWith("H"));
        assertTrue(out.endsWith("T"));
        assertTrue(out.contains("truncated"));
    }

    @Test
    void dropsOlderTurnsAndStartsWithUser() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "U".repeat(80)),
                new ChatMessage("assistant", "A".repeat(80)),
                new ChatMessage("user", "Z".repeat(80)));
        List<ChatMessage> fit = TextBudget.fitHistory(history, 100);
        assertEquals(1, fit.size());
        assertEquals("user", fit.get(0).role());
        assertTrue(fit.get(0).text().contains("omitted"));
        assertTrue(fit.get(0).text().contains("Z"));
        assertEquals(3, TextBudget.fitHistory(history, 1000).size());

        List<ChatMessage> endingOnAssistant = List.of(
                new ChatMessage("user", "U".repeat(80)),
                new ChatMessage("assistant", "A".repeat(40)));
        List<ChatMessage> kept = TextBudget.fitHistory(endingOnAssistant, 50);
        assertEquals("user", kept.get(0).role());
        assertEquals("assistant", kept.get(1).role());
        assertTrue(kept.get(0).text().contains("omitted"));
    }
}
