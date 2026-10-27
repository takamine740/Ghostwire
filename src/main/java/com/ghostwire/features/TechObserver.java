package com.ghostwire.features;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.Cookie;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.ghostwire.llm.ClientLog;
import com.ghostwire.ui.TechView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads responses Burp already received, including Proxy, crawl, and active audit.
 * Does not change the message and does not call the model.
 */
public final class TechObserver implements HttpHandler {

    private final MontoyaApi api;
    private final TechView view;
    private final ClientLog log;

    public TechObserver(MontoyaApi api, TechView view, ClientLog log) {
        this.api = api;
        this.view = view;
        this.log = log;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(burp.api.montoya.http.handler.HttpRequestToBeSent requestToBeSent) {
        return RequestToBeSentAction.continueWith(requestToBeSent);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        try {
            observe(response);
        } catch (RuntimeException ex) {
            log.error("Ghostwire: technology check skipped a response.");
        }
        return ResponseReceivedAction.continueWith(response);
    }

    private void observe(HttpResponseReceived response) {
        if (response == null) {
            return;
        }
        HttpRequest request = response.initiatingRequest();
        if (request == null) {
            return;
        }
        String url = request.url();
        if (url == null || url.isBlank() || !inScope(url)) {
            return;
        }
        String host = request.httpService() == null ? "" : request.httpService().host();
        if (host == null || host.isBlank()) {
            return;
        }
        List<TechFingerprints.Header> headers = new ArrayList<>();
        String contentType = "";
        for (HttpHeader header : response.headers()) {
            if (header == null || header.name() == null) {
                continue;
            }
            headers.add(new TechFingerprints.Header(header.name(), header.value()));
            if (header.name().equalsIgnoreCase("content-type")) {
                contentType = header.value();
            }
        }
        List<String> cookies = new ArrayList<>();
        for (Cookie cookie : response.cookies()) {
            if (cookie != null && cookie.name() != null) {
                cookies.add(cookie.name());
            }
        }
        String body = "";
        String mime = response.mimeType() == null ? null : response.mimeType().name();
        if (response.body() != null && response.body().length() > 0 && ScanFilter.textResponse(contentType, mime)) {
            body = pageText(response.body());
        }
        List<TechHit> hits = TechFingerprints.match(url, headers, cookies, body);
        if (hits.isEmpty()) {
            return;
        }
        String source = response.toolSource() == null || response.toolSource().toolType() == null
                ? ""
                : response.toolSource().toolType().toolName();
        view.observe(hits, host, source, url);
    }

    private static String pageText(ByteArray body) {
        if (body.length() <= TechFingerprints.FULL_BODY) {
            return new String(body.getBytes(), StandardCharsets.UTF_8);
        }
        int head = Math.min(body.length(), TechFingerprints.HEAD_BYTES);
        StringBuilder out = new StringBuilder(head + 4096);
        out.append(new String(body.subArray(0, head).getBytes(), StandardCharsets.UTF_8));
        for (String marker : TechFingerprints.markers()) {
            int from = 0;
            int added = 0;
            while (added < 40 && out.length() < 500_000) {
                int at = body.indexOf(marker, false, from, body.length());
                if (at < 0) {
                    break;
                }
                from = at + Math.max(1, marker.length());
                if (at + 1500 <= head) {
                    continue;
                }
                int start = Math.max(head, at - 120);
                int end = Math.min(body.length(), at + 1500);
                out.append('\n').append(new String(body.subArray(start, end).getBytes(), StandardCharsets.UTF_8));
                added++;
            }
        }
        return out.toString();
    }

    private boolean inScope(String url) {
        try {
            return api.scope().isInScope(url);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
