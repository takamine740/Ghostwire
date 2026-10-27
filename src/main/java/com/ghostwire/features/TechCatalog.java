package com.ghostwire.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Per-host technology inventory. A later version fills in a row that had none. */
public final class TechCatalog {

    public static final String KEY = "gw.tech";
    public static final int MAX_ENTRIES = 800;

    public record Record(String product, String version, String category, String purl, String host, String source, String evidence, String url, String seen) {}

    private TechCatalog() {}

    public static Record merge(List<Record> records, TechHit hit, String host, String source, String url, String seen) {
        if (hit == null || hit.product().isBlank() || host == null || host.isBlank()) {
            return null;
        }
        String version = hit.version() == null ? "" : hit.version();
        int blank = -1;
        int exact = -1;
        boolean seenProduct = false;
        for (int i = 0; i < records.size(); i++) {
            Record record = records.get(i);
            if (!record.product().equalsIgnoreCase(hit.product()) || !record.host().equalsIgnoreCase(host)) {
                continue;
            }
            seenProduct = true;
            if (record.version().equals(version)) {
                exact = i;
            } else if (record.version().isBlank()) {
                blank = i;
            }
        }
        if (exact >= 0) {
            return fillBlankFields(records, exact, hit);
        }
        if (!version.isBlank() && blank >= 0) {
            Record previous = records.get(blank);
            Record updated = new Record(previous.product(), version, chosen(hit.category(), previous.category()),
                    chosen(hit.purl(), previous.purl()), previous.host(), blank(source), blank(hit.evidence()),
                    clip(url, 500), blank(seen));
            records.set(blank, updated);
            return updated;
        }
        if (version.isBlank() && seenProduct) {
            return null;
        }
        if (records.size() >= MAX_ENTRIES) {
            return null;
        }
        Record created = new Record(hit.product(), version, blank(hit.category()), blank(hit.purl()), host,
                blank(source), blank(hit.evidence()), clip(url, 500), blank(seen));
        records.add(created);
        return created;
    }

    private static Record fillBlankFields(List<Record> records, int index, TechHit hit) {
        Record record = records.get(index);
        String category = record.category().isBlank() ? blank(hit.category()) : record.category();
        String purl = record.purl().isBlank() ? blank(hit.purl()) : record.purl();
        if (category.equals(record.category()) && purl.equals(record.purl())) {
            return null;
        }
        Record updated = new Record(record.product(), record.version(), category, purl, record.host(),
                record.source(), record.evidence(), record.url(), record.seen());
        records.set(index, updated);
        return updated;
    }

    public static String toJson(List<Record> records) {
        JsonArray array = new JsonArray();
        for (Record record : records) {
            JsonObject obj = new JsonObject();
            obj.addProperty("product", record.product());
            obj.addProperty("version", record.version());
            obj.addProperty("category", record.category());
            obj.addProperty("purl", record.purl());
            obj.addProperty("host", record.host());
            obj.addProperty("source", record.source());
            obj.addProperty("evidence", record.evidence());
            obj.addProperty("url", record.url());
            obj.addProperty("seen", record.seen());
            array.add(obj);
        }
        return array.toString();
    }

    public static List<Record> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                return new ArrayList<>();
            }
            List<Record> records = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String product = text(obj, "product");
                String host = text(obj, "host");
                if (product.isBlank() || host.isBlank()) {
                    continue;
                }
                records.add(new Record(product, text(obj, "version"), text(obj, "category"), text(obj, "purl"), host,
                        text(obj, "source"), text(obj, "evidence"), text(obj, "url"), text(obj, "seen")));
                if (records.size() >= MAX_ENTRIES) {
                    break;
                }
            }
            return records;
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    public static String exportCsv(List<Record> records) {
        StringBuilder out = new StringBuilder("product,version,category,package_url,host,source,evidence,url,seen\n");
        for (Record record : records) {
            out.append(csv(record.product())).append(',')
                    .append(csv(record.version())).append(',')
                    .append(csv(record.category())).append(',')
                    .append(csv(record.purl())).append(',')
                    .append(csv(record.host())).append(',')
                    .append(csv(record.source())).append(',')
                    .append(csv(record.evidence())).append(',')
                    .append(csv(record.url())).append(',')
                    .append(csv(record.seen())).append('\n');
        }
        return out.toString();
    }

    private static String text(JsonObject obj, String name) {
        if (!obj.has(name) || obj.get(name).isJsonNull() || !obj.get(name).isJsonPrimitive()) {
            return "";
        }
        return obj.get(name).getAsString();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String chosen(String preferred, String fallback) {
        String value = blank(preferred);
        return value.isBlank() ? blank(fallback) : value;
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String csv(String value) {
        String text = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + text + "\"";
    }
}
