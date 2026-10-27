package com.ghostwire.context;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.llm.ChatMessage;

import java.util.List;
import java.util.function.Supplier;

/**
 * Turns Burp messages into a bounded prompt: allow-listed headers, head-and-tail bodies,
 * binary skipped, optional redaction, and a hard character cap.
 */
public final class ContextBudgeter {

    private final GhostwireConfig config;

    public ContextBudgeter(GhostwireConfig config) {
        this.config = config;
    }

    public String formatExchange(HttpRequest request, HttpResponse response) {
        StringBuilder text = new StringBuilder();
        text.append(formatRequest(request));
        if (response != null) {
            text.append("\n\n");
            text.append(formatResponse(response));
        }
        return TextBudget.headAndTail(text.toString(), config.maxInputChars());
    }

    public String formatRequest(HttpRequest request) {
        StringBuilder text = new StringBuilder();
        String start = request.method() + " " + request.path() + " " + request.httpVersion();
        text.append(config.redactionEnabled() ? TextBudget.redactSecrets(start) : start).append('\n');
        appendHeaders(text, request.headers());
        text.append('\n');
        appendBody(text, headerValue(request.headers(), "content-type"), null, request.body().length(), request::bodyToString);
        return text.toString();
    }

    public String formatResponse(HttpResponse response) {
        StringBuilder text = new StringBuilder();
        String reason = response.reasonPhrase() == null ? "" : response.reasonPhrase();
        text.append(response.httpVersion()).append(' ').append(response.statusCode()).append(' ').append(reason).append('\n');
        appendHeaders(text, response.headers());
        text.append('\n');
        appendBody(text, headerValue(response.headers(), "content-type"), response.mimeType(), response.body().length(), response::bodyToString);
        return text.toString();
    }

    public List<ChatMessage> fitHistory(List<ChatMessage> history) {
        return TextBudget.fitHistory(history, config.maxInputChars());
    }

    private void appendHeaders(StringBuilder text, List<HttpHeader> headers) {
        boolean redact = config.redactionEnabled();
        for (HttpHeader header : headers) {
            if (TextBudget.keepHeader(header.name())) {
                text.append(TextBudget.formatHeader(header.name(), header.value(), redact)).append('\n');
            }
        }
    }

    private void appendBody(StringBuilder text, String contentType, MimeType mime, int length, Supplier<String> raw) {
        String mimeName = mime == null ? null : mime.name();
        if (TextBudget.isBinary(contentType, mimeName)) {
            String kind = contentType != null && !contentType.isBlank()
                    ? contentType
                    : (mime == null ? "binary" : mime.description());
            text.append("[binary content omitted: ").append(kind).append(", ").append(length).append(" bytes]");
            return;
        }
        int cap = Math.max(2000, config.maxInputChars() / 2);
        String value = raw.get();
        String clipped = TextBudget.headAndTail(value == null ? "" : value, cap);
        text.append(config.redactionEnabled() ? TextBudget.redactSecrets(clipped) : clipped);
    }

    private static String headerValue(List<HttpHeader> headers, String name) {
        for (HttpHeader header : headers) {
            if (header.name().equalsIgnoreCase(name)) {
                return header.value();
            }
        }
        return null;
    }
}
