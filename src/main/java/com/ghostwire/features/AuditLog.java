package com.ghostwire.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** JSON storage and text export for the Repeater audit log. */
public final class AuditLog {

    public static final String KEY = "gw.audit";
    public static final int MAX_ENTRIES = 200;

    private AuditLog() {}

    public static String toJson(List<AuditEntry> entries) {
        JsonArray array = new JsonArray();
        for (AuditEntry entry : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("time", entry.time());
            obj.addProperty("result", entry.result());
            obj.addProperty("method", entry.method());
            obj.addProperty("url", entry.url());
            obj.addProperty("status", entry.status());
            obj.addProperty("reason", entry.reason());
            array.add(obj);
        }
        return array.toString();
    }

    public static List<AuditEntry> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                return List.of();
            }
            List<AuditEntry> out = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                out.add(AuditEntry.of(
                        text(obj, "time"),
                        text(obj, "result"),
                        text(obj, "method"),
                        text(obj, "url"),
                        text(obj, "status"),
                        text(obj, "reason")));
            }
            if (out.size() > MAX_ENTRIES) {
                return List.copyOf(out.subList(out.size() - MAX_ENTRIES, out.size()));
            }
            return List.copyOf(out);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    public static String exportText(List<AuditEntry> entries) {
        StringBuilder out = new StringBuilder("time | result | method | url | status | reason\n");
        for (AuditEntry entry : entries) {
            out.append(one(entry.time())).append(" | ")
                    .append(one(entry.result())).append(" | ")
                    .append(one(entry.method())).append(" | ")
                    .append(one(entry.url())).append(" | ")
                    .append(one(entry.status())).append(" | ")
                    .append(one(entry.reason())).append('\n');
        }
        return out.toString();
    }

    private static String text(JsonObject obj, String name) {
        if (!obj.has(name) || obj.get(name).isJsonNull()) {
            return "";
        }
        JsonElement element = obj.get(name);
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String one(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
