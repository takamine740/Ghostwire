package com.ghostwire.features;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.context.TextBudget;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ChatMessage;
import com.ghostwire.llm.ClientLog;
import com.ghostwire.llm.ConverseResult;

import java.util.List;
import java.util.Locale;

/**
 * One Repeater task: the model proposes a single request, guardrails check it, the operator
 * approves it, Burp sends it, and the response goes back to the model. Runs off the Swing thread.
 */
public final class RepeaterLoop {

    private static final int MAX_RAW = 64_000;
    private static final int MAX_HISTORY = 4_000;

    private final MontoyaApi api;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ContextBudgeter budgeter;
    private final ClientLog log;

    public RepeaterLoop(MontoyaApi api, Prompts prompts, BedrockConverseClient client,
                        ContextBudgeter budgeter, ClientLog log) {
        this.api = api;
        this.prompts = prompts;
        this.client = client;
        this.budgeter = budgeter;
        this.log = log;
    }

    public void run(HttpRequestResponse seed, RepeaterLimits limits, RepeaterControls controls) {
        try {
            runLoop(seed, limits, controls);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finishStopped(controls);
        } catch (Exception ex) {
            if (causedByInterrupt(ex)) {
                Thread.currentThread().interrupt();
                finishStopped(controls);
                return;
            }
            String message = client.safeMessage(ex);
            controls.setStatus("Repeater stopped. " + message);
            controls.record("error", "", "", "", message);
            log.error("Ghostwire: Repeater stopped. " + message);
        }
    }

    private void runLoop(HttpRequestResponse seed, RepeaterLimits limits, RepeaterControls controls) throws Exception {
        HttpRequest request = readRequest(seed);
        if (request == null) {
            controls.setStatus("Could not read that request. Nothing was sent.");
            return;
        }
        String seedUrl = safeUrl(request);
        if (seedUrl.isBlank() || !inScope(seedUrl)) {
            controls.setStatus("That request is outside Burp scope. Nothing was sent.");
            controls.record("blocked", safeMethod(request), redact(seedUrl), "", "outside scope");
            return;
        }
        HttpRequest origin = request;
        HttpResponse response = readResponse(seed);
        String history = "none yet";
        int sent = 0;
        int turns = 0;
        int maxTurns = limits.maxRequests() + 3;
        while (sent < limits.maxRequests() && turns < maxTurns && !controls.stopped()) {
            turns++;
            controls.setStatus(limits.approveEach()
                    ? "Asking the model for the next request. Nothing is sent until you approve it."
                    : "Asking the model for the next request.");
            String user = userPrompt(prompts.get(Prompts.REPEATER), budgeter.formatExchange(request, response), history);
            ConverseResult result = client.converse(List.of(new ChatMessage("user", user)), prompts.systemPrompt(seedUrl));
            if (controls.stopped()) {
                finishStopped(controls);
                return;
            }
            ProposalParser.Proposal proposal = ProposalParser.parse(result.text()).orElse(null);
            if (proposal == null) {
                controls.setStatus("The model reply was not a request. Nothing further was sent.");
                controls.record("error", "", redact(safeUrl(request)), "", "reply was not a request");
                return;
            }
            if (proposal.done()) {
                controls.setStatus("Model finished. " + proposal.reason());
                controls.record("done", "", redact(safeUrl(request)), "", proposal.reason());
                log.info("Ghostwire: Repeater finished. " + clip(proposal.reason(), 200));
                return;
            }
            Prepared prepared = prepare(origin, proposal.request());
            if (!prepared.ok()) {
                controls.setStatus(prepared.blockReason + " Nothing was sent.");
                controls.record("blocked", prepared.method, redact(prepared.url), "", prepared.blockReason);
                log.info("Ghostwire: Repeater blocked a proposal. " + prepared.blockReason);
                return;
            }
            boolean wait = limits.approveEach() || MethodPolicy.requiresApproval(prepared.request.method());
            String shownUrl = redact(prepared.url);
            Decision decision = controls.decide(
                    prepared.request.method(),
                    shownUrl,
                    proposal.reason(),
                    prepared.request.toString(),
                    wait);
            if (decision != Decision.SEND || controls.stopped()) {
                if (decision == Decision.SKIP && !controls.stopped()) {
                    history = appendHistory(history, "skipped " + prepared.request.method() + " " + redact(prepared.request.path())
                            + ". " + redact(proposal.reason()));
                    controls.record("skipped", prepared.request.method(), shownUrl, "", proposal.reason());
                    controls.setStatus("Skipped. Asking for a different request.");
                    continue;
                }
                finishStopped(controls);
                return;
            }
            if (sent > 0 && limits.intervalMillis() > 0) {
                controls.setStatus("Waiting " + (limits.intervalMillis() / 1000) + "s before the next send.");
                pause(limits.intervalMillis(), controls);
            }
            if (controls.stopped()) {
                finishStopped(controls);
                return;
            }
            HttpRequestResponse sentMessage;
            try {
                sentMessage = api.http().sendRequest(prepared.request, RequestOptions.requestOptions()
                        .withRedirectionMode(RedirectionMode.NEVER)
                        .withResponseTimeout(60_000));
            } catch (RuntimeException ex) {
                controls.setStatus("The request was not sent.");
                controls.record("error", prepared.request.method(), shownUrl, "", "send failed");
                log.error("Ghostwire: Repeater send failed. " + client.safeMessage(ex));
                return;
            }
            sent++;
            HttpResponse got = sentMessage == null ? null : readResponse(sentMessage);
            String status = statusCode(got);
            controls.record("sent", prepared.request.method(), shownUrl, status, proposal.reason());
            log.info("Ghostwire: Repeater sent " + prepared.request.method() + " " + shownUrl
                    + " -> " + (status.isBlank() ? "no response" : status));
            if (got == null) {
                controls.setStatus("Sent " + sent + " of " + limits.maxRequests() + ". No response came back.");
                return;
            }
            HttpRequest sentRequest = sentMessage.request();
            request = sentRequest == null ? prepared.request : sentRequest;
            response = got;
            history = appendHistory(history, "sent " + request.method() + " " + redact(request.path())
                    + " -> " + status + ". " + redact(proposal.reason()));
            controls.setStatus("Sent " + sent + " of " + limits.maxRequests() + ". HTTP " + status + ".");
        }
        if (controls.stopped()) {
            finishStopped(controls);
        } else if (sent >= limits.maxRequests()) {
            controls.setStatus("Reached the request cap (" + limits.maxRequests() + ").");
            controls.record("done", "", "", "", "request cap");
        } else {
            controls.setStatus("Stopped after too many skipped proposals.");
            controls.record("done", "", "", "", "too many skips");
        }
    }

    static String userPrompt(String template, String exchange, String history) {
        String body = template == null ? "" : template;
        if (body.contains("{{EXCHANGE}}") || body.contains("{{HISTORY}}")) {
            return body.replace("{{EXCHANGE}}", exchange == null ? "" : exchange)
                    .replace("{{HISTORY}}", history == null ? "" : history);
        }
        return body + "\n\nCurrent exchange:\n" + exchange + "\n\nSteps already taken:\n" + history;
    }

    private Prepared prepare(HttpRequest seed, String raw) {
        String method = firstLineMethod(raw);
        String fallbackUrl = safeUrl(seed);
        if (raw == null || raw.isBlank()) {
            return Prepared.blocked(method, fallbackUrl, "The model did not include a request.");
        }
        if (raw.length() > MAX_RAW) {
            return Prepared.blocked(method, fallbackUrl, "The proposed request is too large.");
        }
        if (!MethodPolicy.allowed(method)) {
            String shown = method.isBlank() ? "missing" : method;
            return Prepared.blocked(method, fallbackUrl,
                    "Method " + shown + " is not allowed. Allowed methods are GET, HEAD, OPTIONS, POST, PUT, DELETE, and PATCH.");
        }
        try {
            HttpService service = seed.httpService();
            String canonical = RequestText.pinHost(raw, HostPin.hostValue(service.host(), service.port(), service.secure()));
            if (canonical == null) {
                return Prepared.blocked(method, fallbackUrl, "The proposed request could not be read.");
            }
            HttpRequest parsed = HttpRequest.httpRequest(service, canonical);
            HttpRequest pinned = retarget(parsed, service);
            if (!sameService(service, pinned.httpService())) {
                return Prepared.blocked(method, fallbackUrl, "The proposed host does not match the starting request.");
            }
            String url = pinned.url();
            if (url == null || url.isBlank() || !inScope(url)) {
                return Prepared.blocked(methodOf(pinned, method), url == null ? fallbackUrl : url,
                        "The proposed URL is outside Burp scope.");
            }
            return Prepared.ok(pinned, url);
        } catch (RuntimeException ex) {
            log.info("Ghostwire: Repeater could not read a proposal. " + ex.getClass().getSimpleName());
            return Prepared.blocked(method, fallbackUrl, "The proposed request could not be read.");
        }
    }

    private void pause(int millis, RepeaterControls controls) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (controls.stopped() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    private void finishStopped(RepeaterControls controls) {
        controls.setStatus("Stopped. No further requests will be sent.");
        controls.record("stopped", "", "", "", "kill switch");
        log.info("Ghostwire: Repeater stopped.");
    }

    private boolean inScope(String url) {
        try {
            return api.scope().isInScope(url);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static HttpRequest readRequest(HttpRequestResponse message) {
        if (message == null) {
            return null;
        }
        try {
            return message.request();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static HttpResponse readResponse(HttpRequestResponse message) {
        if (message == null) {
            return null;
        }
        try {
            return message.response();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static HttpRequest retarget(HttpRequest parsed, HttpService service) {
        try {
            return parsed.withService(service);
        } catch (RuntimeException ex) {
            return parsed;
        }
    }

    private static boolean sameService(HttpService left, HttpService right) {
        return left != null && right != null
                && left.host().equalsIgnoreCase(right.host())
                && left.port() == right.port()
                && left.secure() == right.secure();
    }

    private static String firstLineMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String line = raw.strip().split("\n", 2)[0].trim();
        int space = line.indexOf(' ');
        if (space <= 0) {
            return "";
        }
        String method = line.substring(0, space).trim().toUpperCase(Locale.ROOT);
        return method.length() <= 20 ? method : "";
    }

    private static String methodOf(HttpRequest request, String fallback) {
        String method = safeMethod(request);
        return method.isBlank() ? fallback : method;
    }

    private static String safeMethod(HttpRequest request) {
        if (request == null) {
            return "";
        }
        try {
            return request.method() == null ? "" : request.method();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String safeUrl(HttpRequest request) {
        if (request == null) {
            return "";
        }
        try {
            String url = request.url();
            return url == null ? "" : url;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String statusCode(HttpResponse response) {
        if (response == null) {
            return "";
        }
        try {
            return Integer.toString(response.statusCode());
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String redact(String value) {
        return TextBudget.redactSecrets(value == null ? "" : value);
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String appendHistory(String history, String line) {
        String next = "none yet".equals(history) ? line : history + "\n" + line;
        if (next.length() <= MAX_HISTORY) {
            return next;
        }
        return next.substring(next.length() - MAX_HISTORY);
    }

    private static boolean causedByInterrupt(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private static final class Prepared {
        private final HttpRequest request;
        private final String url;
        private final String method;
        private final String blockReason;

        private Prepared(HttpRequest request, String url, String method, String blockReason) {
            this.request = request;
            this.url = url == null ? "" : url;
            this.method = method == null ? "" : method;
            this.blockReason = blockReason == null ? "" : blockReason;
        }

        private static Prepared ok(HttpRequest request, String url) {
            return new Prepared(request, url, request.method(), "");
        }

        private static Prepared blocked(String method, String url, String reason) {
            return new Prepared(null, url, method, reason);
        }

        private boolean ok() {
            return request != null;
        }
    }
}
