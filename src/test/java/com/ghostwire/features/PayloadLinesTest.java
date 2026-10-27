package com.ghostwire.features;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadLinesTest {

    @Test
    void keepsPayloadLinesAndDropsProse() {
        String reply = """
                Here are some payloads for the parameter you asked about in this particular case today:
                1. ' OR 1=1--
                - <script>alert(1)</script>
                "admin"
                ```
                this line has far too many spaces and reads like an explanation of the payload rather than a payload
                """;
        List<String> payloads = PayloadLines.parse(reply);
        assertEquals(List.of("' OR 1=1--", "<script>alert(1)</script>", "admin"), payloads);
    }

    @Test
    void capsTheList() {
        StringBuilder reply = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            reply.append("p").append(i).append('\n');
        }
        assertEquals(PayloadLines.MAX_PAYLOADS, PayloadLines.parse(reply.toString()).size());
    }

    @Test
    void emptyReplyYieldsNothing() {
        assertTrue(PayloadLines.parse("   \n```\n").isEmpty());
    }
}
