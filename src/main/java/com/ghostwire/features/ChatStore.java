package com.ghostwire.features;

import burp.api.montoya.persistence.PersistedObject;
import com.ghostwire.llm.ChatMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Per-project chat history. Stored in the Burp project, not in user preferences. */
public final class ChatStore {

    private static final String KEY = "gw.chats";
    private static final int MAX_SESSIONS = 15;

    private final PersistedObject data;

    public ChatStore(PersistedObject data) {
        this.data = data;
    }

    public List<ChatSession> load() {
        String json = data.getString(KEY);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<ChatSession> sessions = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String title = text(obj, "title");
                String url = text(obj, "url");
                if (title.isBlank() || url.isBlank()) {
                    continue;
                }
                ChatSession session = new ChatSession(title, url);
                if (obj.has("note") && obj.get("note").isJsonPrimitive()) {
                    session.setNote(obj.get("note").getAsString());
                }
                if (obj.has("messages") && obj.get("messages").isJsonArray()) {
                    for (JsonElement messageElement : obj.getAsJsonArray("messages")) {
                        if (!messageElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject message = messageElement.getAsJsonObject();
                        String role = text(message, "role");
                        String body = text(message, "text");
                        if (("user".equals(role) || "assistant".equals(role)) && !body.isEmpty()) {
                            session.add(new ChatMessage(role, body));
                        }
                    }
                }
                sessions.add(session);
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return sessions;
    }

    public void save(List<ChatSession> sessions) {
        JsonArray array = new JsonArray();
        int count = 0;
        for (ChatSession session : sessions) {
            if (count >= MAX_SESSIONS) {
                break;
            }
            count++;
            JsonObject obj = new JsonObject();
            obj.addProperty("title", session.title());
            obj.addProperty("url", session.url());
            if (session.note() != null) {
                obj.addProperty("note", session.note());
            }
            JsonArray messages = new JsonArray();
            for (ChatMessage message : session.messages()) {
                JsonObject item = new JsonObject();
                item.addProperty("role", message.role());
                item.addProperty("text", message.text());
                messages.add(item);
            }
            obj.add("messages", messages);
            array.add(obj);
        }
        data.setString(KEY, array.toString());
    }

    private static String text(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
