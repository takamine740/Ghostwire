package com.ghostwire.features;

import com.ghostwire.config.Prompts;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalParserTest {

    @Test
    void readsTheLineFormat() {
        String reply = """
                ACTION: send
                REASON: Confirm the title on the homepage.
                REQUEST:
                GET / HTTP/1.1
                Host: www.example.com

                """;
        ProposalParser.Proposal proposal = ProposalParser.parse(reply).orElseThrow();
        assertTrue(proposal.send());
        assertEquals("Confirm the title on the homepage.", proposal.reason());
        assertTrue(proposal.request().startsWith("GET / HTTP/1.1\nHost: www.example.com"));
    }

    @Test
    void readsDoneWithoutARequest() {
        Optional<ProposalParser.Proposal> proposal = ProposalParser.parse("ACTION: done\r\nREASON: Nothing else to check.\r\n");
        assertTrue(proposal.isPresent());
        assertTrue(proposal.get().done());
        assertEquals("Nothing else to check.", proposal.get().reason());
    }

    @Test
    void readsFencedJson() {
        String reply = """
                ```json
                {"action":"send","reason":"Confirm the path.","request":"GET /robots.txt HTTP/1.1\\nHost: www.example.com"}
                ```
                """;
        ProposalParser.Proposal proposal = ProposalParser.parse(reply).orElseThrow();
        assertTrue(proposal.send());
        assertEquals("GET /robots.txt HTTP/1.1\nHost: www.example.com", proposal.request());
    }

    @Test
    void ignoresProseAndASendWithNoRequest() {
        assertTrue(ProposalParser.parse("I would look at the homepage next.").isEmpty());
        assertTrue(ProposalParser.parse("ACTION: send\nREASON: Missing the request.\n").isEmpty());
    }

    @Test
    void keepsARequestBodyThatContainsBraces() {
        String reply = """
                ACTION: send
                REASON: Resend the form the page already showed.
                REQUEST:
                POST /search HTTP/1.1
                Host: www.example.com
                Content-Type: application/json

                {"q":"alpha"}
                """;
        ProposalParser.Proposal proposal = ProposalParser.parse(reply).orElseThrow();
        assertTrue(proposal.request().contains("{\"q\":\"alpha\"}"));
    }

    @Test
    void promptFillsPlaceholdersOrAppendsTheExchange() {
        assertEquals("see HOME then none", RepeaterLoop.userPrompt("see {{EXCHANGE}} then {{HISTORY}}", "HOME", "none"));
        String plain = RepeaterLoop.userPrompt("Look at this.", "GET /", "none yet");
        assertTrue(plain.contains("GET /"));
        assertTrue(plain.contains("none yet"));
    }

    @Test
    void bundledRepeaterPromptDeclaresItsPlaceholders() {
        String prompt = Prompts.bundled(Prompts.REPEATER);
        assertTrue(prompt.contains("{{EXCHANGE}}"));
        assertTrue(prompt.contains("{{HISTORY}}"));
        assertTrue(prompt.contains("ACTION:"));
    }
}
