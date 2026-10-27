package com.ghostwire.features;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeaterGuardTest {

    @Test
    void safeMethodsCanSkipApprovalAndStateChangesCannot() {
        assertFalse(MethodPolicy.requiresApproval("GET"));
        assertFalse(MethodPolicy.requiresApproval("head"));
        assertFalse(MethodPolicy.requiresApproval("OPTIONS"));
        assertTrue(MethodPolicy.requiresApproval("POST"));
        assertTrue(MethodPolicy.requiresApproval("DELETE"));
        assertTrue(MethodPolicy.requiresApproval(null));
        assertTrue(MethodPolicy.allowed("PATCH"));
        assertFalse(MethodPolicy.allowed("TRACE"));
        assertFalse(MethodPolicy.allowed("CONNECT"));
        assertFalse(MethodPolicy.allowed(""));
    }

    @Test
    void pinnedRequestUsesCrlfAndTheOriginalHost() {
        String raw = "GET https://evil.example/search?q=1 HTTP/2\nAccept: text/html\nHost: evil.example\nHost: also-evil\n";
        String pinned = RequestText.pinHost(raw, "www.example.com");
        assertEquals("GET /search?q=1 HTTP/1.1\r\nAccept: text/html\r\nHost: www.example.com\r\n\r\n", pinned);
    }

    @Test
    void pinnedRequestInsertsHostAndKeepsTheBody() {
        String raw = "POST /search HTTP/1.1\nContent-Type: application/json\n\n{\"q\":\"alpha\"}";
        String pinned = RequestText.pinHost(raw, "www.example.com");
        assertEquals("POST /search HTTP/1.1\r\nHost: www.example.com\r\nContent-Type: application/json\r\n\r\n{\"q\":\"alpha\"}", pinned);
    }

    @Test
    void hostHeaderOmitsTheDefaultPort() {
        assertEquals("www.example.com", HostPin.hostValue("www.example.com", 443, true));
        assertEquals("www.example.com", HostPin.hostValue("www.example.com", 80, false));
        assertEquals("www.example.com:8443", HostPin.hostValue("www.example.com", 8443, true));
        assertEquals("www.example.com:8080", HostPin.hostValue("www.example.com", 8080, false));
    }

    @Test
    void auditLogRoundTripsAndDropsCorruptInput() {
        AuditEntry entry = AuditEntry.of("2026-10-24T02:00:00", "sent", "GET", "https://www.example.com/a", "200", "Check the title.");
        List<AuditEntry> loaded = AuditLog.fromJson(AuditLog.toJson(List.of(entry)));
        assertEquals(List.of(entry), loaded);
        assertTrue(AuditLog.exportText(loaded).contains("sent | GET | https://www.example.com/a | 200 | Check the title."));
        assertTrue(AuditLog.fromJson("not json").isEmpty());
        assertTrue(AuditLog.fromJson("{\"no\":1}").isEmpty());
    }

    @Test
    void auditLogKeepsTheNewestEntries() {
        List<AuditEntry> entries = new ArrayList<>();
        for (int i = 0; i < AuditLog.MAX_ENTRIES + 5; i++) {
            entries.add(AuditEntry.of("t", "sent", "GET", "https://www.example.com/" + i, "200", "n" + i));
        }
        List<AuditEntry> loaded = AuditLog.fromJson(AuditLog.toJson(entries));
        assertEquals(AuditLog.MAX_ENTRIES, loaded.size());
        assertEquals("https://www.example.com/" + (AuditLog.MAX_ENTRIES + 4), loaded.get(loaded.size() - 1).url());
    }

    @Test
    void limitsClampToThePanelRange() {
        RepeaterLimits limits = new RepeaterLimits(0, -5, true);
        assertEquals(1, limits.maxRequests());
        assertEquals(0, limits.intervalMillis());
        assertTrue(limits.approveEach());
    }
}
