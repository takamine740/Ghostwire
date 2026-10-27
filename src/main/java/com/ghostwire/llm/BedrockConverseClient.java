package com.ghostwire.llm;

import com.ghostwire.config.GhostwireConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AWS Bedrock Converse API over HTTPS with a bearer API key. No AWS SDK and no SigV4.
 */
public final class BedrockConverseClient {

    private static final Pattern REGION = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final GhostwireConfig config;
    private final ClientLog log;

    public BedrockConverseClient(GhostwireConfig config, ClientLog log) {
        this.config = config;
        this.log = log;
    }

    public ConverseResult testConnection() throws BedrockException {
        return converse(List.of(new ChatMessage("user", "Reply with exactly the word OK.")), null);
    }

    public ConverseResult converse(List<ChatMessage> messages, String systemPrompt) throws BedrockException {
        if (messages == null || messages.isEmpty()) {
            throw new BedrockException("No messages to send.");
        }
        String apiKey = config.apiKey();
        String region = config.region();
        String modelId = config.modelId();
        if (apiKey.isBlank()) {
            throw new BedrockException("Set a Bedrock API key.");
        }
        if (!REGION.matcher(region).matches()) {
            throw new BedrockException("Set a Bedrock region such as us-east-1.");
        }
        if (modelId.isBlank() || modelId.chars().anyMatch(Character::isWhitespace)) {
            throw new BedrockException("Set the Bedrock model ID or inference profile. Ghostwire does not guess this.");
        }

        if (modelId.startsWith("xai.")) {
            return mantleChat(region, apiKey, modelId, messages, systemPrompt);
        }
        return runtimeConverse(region, apiKey, modelId, messages, systemPrompt);
    }

    /** Grok on Bedrock is called through the Mantle OpenAI chat-completions endpoint, not Converse. */
    private ConverseResult mantleChat(String region, String apiKey, String modelId, List<ChatMessage> messages, String systemPrompt) throws BedrockException {
        URI uri = URI.create("https://bedrock-mantle." + region + ".api.aws/openai/v1/chat/completions");
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("max_tokens", config.maxTokens());
        JsonArray messageArray = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messageArray.add(chatMessage("system", systemPrompt));
        }
        for (ChatMessage message : messages) {
            messageArray.add(chatMessage(message.role(), message.text()));
        }
        body.add("messages", messageArray);
        return post(uri, apiKey, body, BedrockConverseClient::parseChat);
    }

    private ConverseResult runtimeConverse(String region, String apiKey, String modelId, List<ChatMessage> messages, String systemPrompt) throws BedrockException {
        String encodedModel = URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("https://bedrock-runtime." + region + ".amazonaws.com/model/" + encodedModel + "/converse");

        JsonObject body = new JsonObject();
        JsonArray messageArray = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            JsonArray content = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("text", message.text());
            content.add(block);
            item.add("content", content);
            messageArray.add(item);
        }
        body.add("messages", messageArray);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonArray system = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("text", systemPrompt);
            system.add(block);
            body.add("system", system);
        }
        // Opus on Bedrock rejects temperature ("deprecated for this model"), so only maxTokens is sent.
        JsonObject inference = new JsonObject();
        inference.addProperty("maxTokens", config.maxTokens());
        body.add("inferenceConfig", inference);

        return post(uri, apiKey, body, BedrockConverseClient::parse);
    }

    private ConverseResult post(URI uri, String apiKey, JsonObject body, Parser parser) throws BedrockException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new BedrockException(errorMessage(response.body(), response.statusCode()), response.statusCode(), null);
            }
            return parser.parse(response.body());
        } catch (BedrockException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BedrockException("Bedrock call was interrupted.", e);
        } catch (Exception e) {
            throw new BedrockException("Could not reach Bedrock: " + e.getMessage(), e);
        }
    }

    private static JsonObject chatMessage(String role, String text) {
        JsonObject item = new JsonObject();
        item.addProperty("role", role);
        item.addProperty("content", text);
        return item;
    }

    @FunctionalInterface
    private interface Parser {
        ConverseResult parse(String body) throws BedrockException;
    }

    /** Removes the API key from a message before it is shown or logged. */
    public String safeMessage(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String apiKey = config.apiKey();
        if (!apiKey.isEmpty() && message.contains(apiKey)) {
            message = message.replace(apiKey, "<REDACTED:apiKey>");
        }
        if (message.length() > 600) {
            message = message.substring(0, 600) + "...";
        }
        return message;
    }

    private HttpClient httpClient() throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(TlsSupport.context(log));
        String proxyHost = config.proxyHost();
        int proxyPort = config.proxyPort();
        if (!proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }

    private static ConverseResult parse(String body) throws BedrockException {
        if (body == null || body.isBlank()) {
            throw new BedrockException("Bedrock returned an empty response.");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new BedrockException("Bedrock returned a response that was not JSON.");
        }
        StringBuilder text = new StringBuilder();
        JsonObject output = root.has("output") && root.get("output").isJsonObject() ? root.getAsJsonObject("output") : null;
        if (output != null && output.has("message") && output.get("message").isJsonObject()) {
            JsonObject message = output.getAsJsonObject("message");
            if (message.has("content") && message.get("content").isJsonArray()) {
                for (JsonElement element : message.getAsJsonArray("content")) {
                    if (element.isJsonObject() && element.getAsJsonObject().has("text")) {
                        text.append(element.getAsJsonObject().get("text").getAsString());
                    }
                }
            }
        }
        String stop = root.has("stopReason") && root.get("stopReason").isJsonPrimitive()
                ? root.get("stopReason").getAsString() : "";
        int inputTokens = 0;
        int outputTokens = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            inputTokens = usage.has("inputTokens") ? usage.get("inputTokens").getAsInt() : 0;
            outputTokens = usage.has("outputTokens") ? usage.get("outputTokens").getAsInt() : 0;
        }
        return new ConverseResult(text.toString(), stop, inputTokens, outputTokens);
    }

    private static ConverseResult parseChat(String body) throws BedrockException {
        if (body == null || body.isBlank()) {
            throw new BedrockException("Bedrock returned an empty response.");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new BedrockException("Bedrock returned a response that was not JSON.");
        }
        String text = "";
        String stop = "";
        if (root.has("choices") && root.get("choices").isJsonArray() && !root.getAsJsonArray("choices").isEmpty()) {
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive()) {
                stop = choice.get("finish_reason").getAsString();
            }
            if (choice.has("message") && choice.get("message").isJsonObject()) {
                text = chatContent(choice.getAsJsonObject("message").get("content"));
            }
        }
        int inputTokens = 0;
        int outputTokens = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
            outputTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
        }
        return new ConverseResult(text, stop, inputTokens, outputTokens);
    }

    private static String chatContent(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonPrimitive()) {
                text.append(part.getAsString());
            } else if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                text.append(part.getAsJsonObject().get("text").getAsString());
            }
        }
        return text.toString();
    }

    private static String errorMessage(String body, int status) {
        if (body == null || body.isBlank()) {
            return "Bedrock returned HTTP " + status + ".";
        }
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("error") && obj.get("error").isJsonObject()) {
                JsonObject error = obj.getAsJsonObject("error");
                String message = error.has("message") && error.get("message").isJsonPrimitive()
                        ? error.get("message").getAsString() : obj.toString();
                String code = error.has("code") && error.get("code").isJsonPrimitive()
                        ? error.get("code").getAsString() : "";
                return code.isEmpty()
                        ? "Bedrock HTTP " + status + ": " + message
                        : "Bedrock HTTP " + status + " (" + code + "): " + message;
            }
            if (obj.has("message") && obj.get("message").isJsonPrimitive()) {
                return "Bedrock HTTP " + status + ": " + obj.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Response was not JSON.
        }
        String trimmed = body.length() > 400 ? body.substring(0, 400) + "..." : body;
        return "Bedrock HTTP " + status + ": " + trimmed;
    }
}
