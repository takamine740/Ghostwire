package com.ghostwire.features;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.scanner.AuditResult;
import burp.api.montoya.scanner.ConsolidationAction;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence;
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity;
import burp.api.montoya.scanner.scancheck.PassiveScanCheck;
import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.context.TextBudget;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ChatMessage;
import com.ghostwire.llm.ClientLog;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static burp.api.montoya.scanner.ConsolidationAction.KEEP_BOTH;
import static burp.api.montoya.scanner.ConsolidationAction.KEEP_EXISTING;

/**
 * Passive check. Burp calls {@link #doCheck} on a scanner thread. The model call stays on that
 * thread so the issues can be returned to the scan. One call runs at a time.
 */
public final class PassiveReview implements PassiveScanCheck {

    private static final int MAX_SEEN = 400;
    private static final String BACKGROUND = "Ghostwire read this response during a passive scan. Confirm the observation in the request and response before treating it as a finding.";
    private static final String REMEDIATION_BACKGROUND = "Review the observation against the response. Ghostwire does not send a follow-up request.";

    private final MontoyaApi api;
    private final GhostwireConfig config;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ContextBudgeter budgeter;
    private final ClientLog log;
    private final Object modelLock = new Object();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_SEEN;
        }
    };

    public PassiveReview(MontoyaApi api, GhostwireConfig config, Prompts prompts, BedrockConverseClient client,
                         ContextBudgeter budgeter, ClientLog log) {
        this.api = api;
        this.config = config;
        this.prompts = prompts;
        this.client = client;
        this.budgeter = budgeter;
        this.log = log;
    }

    @Override
    public String checkName() {
        return "Ghostwire";
    }

    @Override
    public AuditResult doCheck(HttpRequestResponse base) {
        try {
            if (!config.passiveEnabled() || SwingUtilities.isEventDispatchThread()) {
                return none();
            }
            HttpRequest request = base == null ? null : base.request();
            HttpResponse response = base == null ? null : base.response();
            if (request == null || response == null) {
                return none();
            }
            String url = request.url();
            if (url == null || url.isBlank() || !api.scope().isInScope(url)) {
                return none();
            }
            String mime = response.mimeType() == null ? null : response.mimeType().name();
            if (!ScanFilter.textResponse(contentType(response), mime)) {
                return none();
            }
            String key = ResponseHash.key(url, response.statusCode(), response.body().getBytes());
            if (seen(key)) {
                return none();
            }
            List<AuditIssue> issues;
            synchronized (modelLock) {
                if (!config.passiveEnabled() || seen(key)) {
                    return none();
                }
                String user = IssueNotes.prompt(prompts.get(Prompts.PASSIVE), budgeter.formatExchange(request, response));
                var result = client.converse(List.of(new ChatMessage("user", user)), prompts.systemPrompt(url));
                issues = issues(IssueNotes.parse(result.text()), url, base);
                remember(key);
            }
            log.info("Ghostwire: passive scan reviewed " + TextBudget.redactSecrets(url) + " (" + issues.size() + " issues).");
            return AuditResult.auditResult(issues);
        } catch (RuntimeException ex) {
            log.error("Ghostwire: passive scan skipped a response. " + client.safeMessage(ex));
            return none();
        } catch (Exception ex) {
            log.error("Ghostwire: passive scan skipped a response. " + client.safeMessage(ex));
            return none();
        }
    }

    @Override
    public ConsolidationAction consolidateIssues(AuditIssue existingIssue, AuditIssue newIssue) {
        if (existingIssue != null && newIssue != null && existingIssue.name() != null
                && existingIssue.name().equals(newIssue.name())) {
            return KEEP_EXISTING;
        }
        return KEEP_BOTH;
    }

    private List<AuditIssue> issues(List<IssueNotes.Note> notes, String url, HttpRequestResponse base) {
        List<AuditIssue> issues = new ArrayList<>();
        for (IssueNotes.Note note : notes) {
            AuditIssueSeverity severity = severity(note.severity());
            issues.add(AuditIssue.auditIssue(
                    "Ghostwire: " + note.name(),
                    escape(note.detail()),
                    escape(note.remediation()),
                    url,
                    severity,
                    confidence(note.confidence()),
                    BACKGROUND,
                    REMEDIATION_BACKGROUND,
                    severity,
                    base));
        }
        return issues;
    }

    private boolean seen(String key) {
        synchronized (seen) {
            return seen.containsKey(key);
        }
    }

    private void remember(String key) {
        synchronized (seen) {
            seen.put(key, Boolean.TRUE);
        }
    }

    private static AuditResult none() {
        return AuditResult.auditResult();
    }

    private static AuditIssueSeverity severity(String value) {
        return switch (value) {
            case "high" -> AuditIssueSeverity.HIGH;
            case "medium" -> AuditIssueSeverity.MEDIUM;
            case "low" -> AuditIssueSeverity.LOW;
            default -> AuditIssueSeverity.INFORMATION;
        };
    }

    private static AuditIssueConfidence confidence(String value) {
        return switch (value) {
            case "certain" -> AuditIssueConfidence.CERTAIN;
            case "firm" -> AuditIssueConfidence.FIRM;
            default -> AuditIssueConfidence.TENTATIVE;
        };
    }

    private static String contentType(HttpResponse response) {
        for (HttpHeader header : response.headers()) {
            if ("content-type".equalsIgnoreCase(header.name())) {
                return header.value();
            }
        }
        return "";
    }

    private static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
