package com.ghostwire.features;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.intruder.AttackConfiguration;
import burp.api.montoya.intruder.GeneratedPayload;
import burp.api.montoya.intruder.HttpRequestTemplate;
import burp.api.montoya.intruder.IntruderInsertionPoint;
import burp.api.montoya.intruder.PayloadGenerator;
import burp.api.montoya.intruder.PayloadGeneratorProvider;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.context.TextBudget;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ChatMessage;
import com.ghostwire.llm.ClientLog;

import java.util.List;

/** Intruder payload set. The model is called once, then the values are returned one at a time. */
public final class AiPayloadGeneratorProvider implements PayloadGeneratorProvider {

    private final MontoyaApi api;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ContextBudgeter budgeter;
    private final ClientLog log;

    public AiPayloadGeneratorProvider(MontoyaApi api, Prompts prompts, BedrockConverseClient client,
                                      ContextBudgeter budgeter, ClientLog log) {
        this.api = api;
        this.prompts = prompts;
        this.client = client;
        this.budgeter = budgeter;
        this.log = log;
    }

    @Override
    public String displayName() {
        return "Ghostwire: AI payloads";
    }

    @Override
    public PayloadGenerator providePayloadGenerator(AttackConfiguration attackConfiguration) {
        return new Generator(attackConfiguration);
    }

    private final class Generator implements PayloadGenerator {
        private final AttackConfiguration attack;
        private final Object lock = new Object();
        private List<String> payloads;
        private int index;

        private Generator(AttackConfiguration attack) {
            this.attack = attack;
        }

        @Override
        public GeneratedPayload generatePayloadFor(IntruderInsertionPoint insertionPoint) {
            synchronized (lock) {
                if (payloads == null) {
                    payloads = fetch(insertionPoint);
                }
                while (index < payloads.size()) {
                    String value = payloads.get(index++);
                    if (value != null && !value.isEmpty()) {
                        return GeneratedPayload.payload(ByteArray.byteArray(value));
                    }
                }
                // Burp 2026.9 calls value().getBytes() on GeneratedPayload.end(), whose value is null.
                // A null return is the end-of-payloads signal this build checks for.
                return null;
            }
        }

        private List<String> fetch(IntruderInsertionPoint insertionPoint) {
            HttpRequestTemplate template = attack.requestTemplate();
            HttpRequest request = templateRequest(template);
            if (request == null) {
                log.error("Ghostwire: Intruder payloads skipped. The request could not be read.");
                return List.of();
            }
            String url;
            try {
                url = request.url();
            } catch (RuntimeException ex) {
                log.error("Ghostwire: Intruder payloads skipped. The request URL could not be read.");
                return List.of();
            }
            if (!api.scope().isInScope(url)) {
                log.info("Ghostwire: Intruder payloads skipped. Outside scope: " + TextBudget.redactSecrets(url));
                return List.of();
            }
            String base = "";
            try {
                if (insertionPoint != null && insertionPoint.baseValue() != null) {
                    base = insertionPoint.baseValue().toString();
                }
            } catch (RuntimeException ex) {
                log.error("Ghostwire: Intruder base value could not be read.");
            }
            ByteArray content = template == null ? null : template.content();
            String context = contextAround(content, base);
            String user = fill(prompts.get(Prompts.INTRUDER), base, context, budgeter.formatRequest(request));
            log.info("Ghostwire: generating Intruder payloads for " + TextBudget.redactSecrets(url));
            try {
                String reply = client.converse(List.of(new ChatMessage("user", user)), prompts.systemPrompt(url)).text();
                List<String> parsed = PayloadLines.parse(reply);
                if (parsed.isEmpty()) {
                    log.error("Ghostwire: the model returned no Intruder payloads.");
                    return List.of();
                }
                log.info("Ghostwire: Intruder payload set has " + parsed.size() + " values.");
                return parsed;
            } catch (Exception ex) {
                log.error("Ghostwire: Intruder payload generation failed: " + client.safeMessage(ex));
                return List.of();
            }
        }

        /** Payload markers make {@code httpService()} empty and can break request parsing. */
        private HttpRequest templateRequest(HttpRequestTemplate template) {
            if (template == null || template.content() == null) {
                return null;
            }
            String raw = template.content().toString().replace("\u00C2\u00A7", "").replace("\u00A7", "");
            try {
                if (attack.httpService().isPresent()) {
                    return HttpRequest.httpRequest(attack.httpService().get(), raw);
                }
                return HttpRequest.httpRequest(raw);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    /** Burp 2026 throws from {@code insertionPointOffsets()}, so the base value is located in the raw request. */
    private static String contextAround(ByteArray content, String base) {
        if (content == null || base == null || base.isEmpty()) {
            return "";
        }
        String raw = content.toString();
        int at = raw.indexOf(base);
        if (at < 0) {
            return base;
        }
        int start = Math.max(0, at - 80);
        int end = Math.min(raw.length(), at + base.length() + 40);
        return raw.substring(start, end);
    }

    private static String fill(String template, String base, String context, String request) {
        if (template.contains("{{BASE}}") || template.contains("{{CONTEXT}}") || template.contains("{{REQUEST}}")) {
            return template.replace("{{BASE}}", base).replace("{{CONTEXT}}", context).replace("{{REQUEST}}", request);
        }
        return template + "\n\nBase value:\n" + base + "\n\nContext:\n" + context + "\n\nRequest:\n" + request;
    }
}
