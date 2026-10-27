package com.ghostwire.features;

import com.ghostwire.context.TextBudget;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic technology matches from the bundled rules file.
 * A version is copied from the page, never guessed. Package URLs are filled only when that version is present.
 * Script and link addresses are read from the page and attributed to the page that contained them.
 */
public final class TechFingerprints {

    public static final int FULL_BODY = 262_144;
    public static final int HEAD_BYTES = 131_072;

    public record Header(String name, String value) {}

    private static final Pattern META_TAG = Pattern.compile("(?is)<meta\\b([^>]*)>");
    private static final Pattern SCRIPT_TAG = Pattern.compile("(?is)<script\\b([^>]*)>");
    private static final Pattern LINK_TAG = Pattern.compile("(?is)<link\\b([^>]*)>");
    private static final Pattern ATTR = Pattern.compile("(?is)\\b([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern SRC_ATTR = Pattern.compile("(?is)\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern HREF_ATTR = Pattern.compile("(?is)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private static final Rules RULES = Rules.load();
    private static final List<String> MARKERS = markers(RULES);

    private TechFingerprints() {}

    /** Markers the response sampler searches for past the top of a long page. */
    public static List<String> markers() {
        return MARKERS;
    }

    /**
     * For a long page, keep the top and the regions around script, link, and version markers.
     * Shorter pages are returned unchanged.
     */
    public static String excerpt(String body) {
        if (body == null || body.isEmpty() || body.length() <= FULL_BODY) {
            return body == null ? "" : body;
        }
        int head = Math.min(body.length(), HEAD_BYTES);
        StringBuilder out = new StringBuilder(head + 4096);
        out.append(body, 0, head);
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            String needle = marker.toLowerCase(Locale.ROOT);
            int from = 0;
            int added = 0;
            while (added < 40 && out.length() < 500_000) {
                int at = lower.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                from = at + Math.max(1, needle.length());
                if (at + 1500 <= head) {
                    continue;
                }
                int start = Math.max(head, at - 120);
                int end = Math.min(body.length(), at + 1500);
                out.append('\n').append(body, start, end);
                added++;
            }
        }
        return out.toString();
    }

    public static List<TechHit> match(String url, List<Header> headers, List<String> cookieNames, String body) {
        List<TechHit> hits = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        if (headers != null) {
            for (Header header : headers) {
                if (header == null || header.name() == null) {
                    continue;
                }
                matchHeader(hits, replaced, header.name(), header.value() == null ? "" : header.value());
            }
        }
        if (cookieNames != null) {
            for (String name : cookieNames) {
                if (name != null && !name.isBlank()) {
                    matchCookie(hits, replaced, name);
                }
            }
        }
        matchPath(hits, replaced, pathOf(url));
        String page = body == null ? "" : body;
        if (!page.isEmpty()) {
            String lower = page.toLowerCase(Locale.ROOT);
            Map<String, String> metas = metaValues(page);
            for (MetaRule rule : RULES.metas) {
                matchMeta(hits, replaced, rule, metas, lower);
            }
            for (PageRule rule : RULES.pages) {
                matchPage(hits, replaced, rule, page);
            }
            for (String ref : assetRefs(page)) {
                matchAsset(hits, replaced, url, ref);
            }
        }
        return preferVersions(dropReplaced(hits, replaced));
    }

    private static void matchHeader(List<TechHit> hits, Set<String> replaced, String name, String value) {
        for (HeaderRule rule : RULES.headers) {
            if (!rule.matchesName(name)) {
                continue;
            }
            String version = "";
            if (rule.pattern != null) {
                Matcher matcher = rule.pattern.matcher(value);
                if (!matcher.find()) {
                    continue;
                }
                version = groupVersion(matcher, rule.versionGroup);
            }
            String evidence = rule.pattern == null ? "header " + name : "header " + name + ": " + value;
            add(hits, replaced, rule.product, version, rule.category, rule.purl, rule.replaces, evidence);
        }
    }

    private static void matchCookie(List<TechHit> hits, Set<String> replaced, String name) {
        for (CookieRule rule : RULES.cookies) {
            if (rule.pattern.matcher(name).find()) {
                add(hits, replaced, rule.product, "", rule.category, "", "", "cookie " + name);
            }
        }
    }

    private static void matchPath(List<TechHit> hits, Set<String> replaced, String path) {
        if (path.isEmpty()) {
            return;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (PathRule rule : RULES.paths) {
            if (lower.contains(rule.contains)) {
                add(hits, replaced, rule.product, "", rule.category, "", "", "path " + rule.contains);
            }
        }
    }

    private static void matchMeta(List<TechHit> hits, Set<String> replaced, MetaRule rule, Map<String, String> metas, String lowerBody) {
        String content = metas.get(rule.name.toLowerCase(Locale.ROOT));
        if (content == null) {
            return;
        }
        if (!rule.requires.isEmpty() && !lowerBody.contains(rule.requires.toLowerCase(Locale.ROOT))) {
            return;
        }
        String version = "";
        if (rule.contentIsVersion) {
            version = cleanVersion(content);
            if (version.isEmpty()) {
                return;
            }
        } else if (rule.contentPattern != null) {
            Matcher matcher = rule.contentPattern.matcher(content);
            if (!matcher.find()) {
                return;
            }
            version = groupVersion(matcher, rule.versionGroup);
        }
        add(hits, replaced, rule.product, version, rule.category, rule.purl, rule.replaces, "meta " + rule.name);
    }

    private static void matchPage(List<TechHit> hits, Set<String> replaced, PageRule rule, String body) {
        Matcher matcher = rule.pattern.matcher(body);
        if (!matcher.find()) {
            return;
        }
        add(hits, replaced, rule.product, groupVersion(matcher, rule.versionGroup), rule.category, rule.purl, rule.replaces, "page " + rule.product);
    }

    private static void matchAsset(List<TechHit> hits, Set<String> replaced, String pageUrl, String ref) {
        String resolved = resolve(pageUrl, ref);
        if (resolved.isEmpty()) {
            return;
        }
        String withoutQuery = stripQuery(resolved);
        matchPath(hits, replaced, pathOf(resolved));
        for (AssetRule rule : RULES.assets) {
            if (!rule.pathPattern.matcher(withoutQuery).find()) {
                continue;
            }
            String version = "";
            if (rule.versionPattern != null) {
                Matcher matcher = rule.versionPattern.matcher(resolved);
                if (matcher.find()) {
                    version = anyGroupVersion(matcher);
                }
            }
            add(hits, replaced, rule.product, version, rule.category, rule.purl, "", resolved);
        }
    }

    private static void add(List<TechHit> hits, Set<String> replaced, String product, String version, String category,
                            String purlTemplate, String replaces, String evidence) {
        if (product == null || product.isBlank()) {
            return;
        }
        String clean = cleanVersion(version);
        String purl = clean.isEmpty() || purlTemplate == null || purlTemplate.isBlank() ? "" : purlTemplate.replace("{version}", clean);
        hits.add(new TechHit(product, clean, category, purl, clip(TextBudget.redactSecrets(evidence), 180)));
        if (replaces != null && !replaces.isBlank()) {
            replaced.add(replaces.toLowerCase(Locale.ROOT));
        }
    }

    private static List<TechHit> dropReplaced(List<TechHit> hits, Set<String> replaced) {
        if (replaced.isEmpty()) {
            return hits;
        }
        List<TechHit> kept = new ArrayList<>();
        for (TechHit hit : hits) {
            if (!replaced.contains(hit.product().toLowerCase(Locale.ROOT))) {
                kept.add(hit);
            }
        }
        return kept;
    }

    private static List<TechHit> preferVersions(List<TechHit> hits) {
        Map<String, TechHit> best = new LinkedHashMap<>();
        for (TechHit hit : hits) {
            String key = hit.product().toLowerCase(Locale.ROOT);
            TechHit previous = best.get(key);
            if (previous == null || (previous.version().isBlank() && !hit.version().isBlank())) {
                best.put(key, hit);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static Map<String, String> metaValues(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher tags = META_TAG.matcher(body);
        while (tags.find()) {
            String name = "";
            String content = "";
            Matcher attrs = ATTR.matcher(tags.group(1));
            while (attrs.find()) {
                String key = attrs.group(1).toLowerCase(Locale.ROOT);
                String value = first(attrs.group(2), attrs.group(3), attrs.group(4));
                if (key.equals("name") || key.equals("property") || key.equals("http-equiv")) {
                    name = value;
                } else if (key.equals("content")) {
                    content = value;
                }
            }
            if (!name.isBlank() && !values.containsKey(name.toLowerCase(Locale.ROOT))) {
                values.put(name.toLowerCase(Locale.ROOT), content);
            }
        }
        return values;
    }

    private static List<String> assetRefs(String body) {
        List<String> refs = new ArrayList<>();
        collectRefs(refs, SCRIPT_TAG, SRC_ATTR, body);
        collectRefs(refs, LINK_TAG, HREF_ATTR, body);
        return refs;
    }

    private static void collectRefs(List<String> refs, Pattern tags, Pattern attr, String body) {
        Matcher matcher = tags.matcher(body);
        while (matcher.find() && refs.size() < 200) {
            Matcher values = attr.matcher(matcher.group(1));
            if (!values.find()) {
                continue;
            }
            String ref = first(values.group(1), values.group(2));
            if (ref.isBlank() || ref.startsWith("data:") || ref.regionMatches(true, 0, "javascript:", 0, 11)) {
                continue;
            }
            refs.add(ref.trim());
        }
    }

    private static String resolve(String pageUrl, String ref) {
        try {
            if (pageUrl == null || pageUrl.isBlank()) {
                return ref;
            }
            return URI.create(pageUrl).resolve(ref).toString();
        } catch (RuntimeException ex) {
            return ref;
        }
    }

    private static String pathOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String path = URI.create(url).getRawPath();
            return path == null ? "" : path;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String stripQuery(String url) {
        int query = url.indexOf('?');
        int hash = url.indexOf('#');
        int cut = query < 0 ? hash : (hash < 0 ? query : Math.min(query, hash));
        return cut < 0 ? url : url.substring(0, cut);
    }

    private static String groupVersion(Matcher matcher, int group) {
        if (group <= 0 || group > matcher.groupCount()) {
            return "";
        }
        String value = matcher.group(group);
        return value == null ? "" : cleanVersion(value);
    }

    private static String anyGroupVersion(Matcher matcher) {
        for (int group = 1; group <= matcher.groupCount(); group++) {
            String version = groupVersion(matcher, group);
            if (!version.isEmpty()) {
                return version;
            }
        }
        return "";
    }

    static String cleanVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String version = raw.trim();
        if (version.endsWith(".")) {
            version = version.substring(0, version.length() - 1);
        }
        if (version.length() < 3 || version.length() > 32 || !version.contains(".")) {
            return "";
        }
        if (version.charAt(0) < '0' || version.charAt(0) > '9') {
            return "";
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return "";
            }
        }
        return version;
    }

    private static String first(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static List<String> markers(Rules rules) {
        List<String> markers = new ArrayList<>();
        markers.add("<script");
        markers.add("<link");
        markers.add("<meta");
        markers.add("ajs-version-number");
        for (MetaRule rule : rules.metas) {
            if (!rule.requires.isEmpty() && !markers.contains(rule.requires)) {
                markers.add(rule.requires);
            }
        }
        return List.copyOf(markers);
    }

    private record HeaderRule(String header, Pattern headerPattern, Pattern pattern, int versionGroup, String product, String category, String purl, String replaces) {
        boolean matchesName(String name) {
            if (headerPattern != null) {
                return headerPattern.matcher(name).find();
            }
            return header != null && header.equalsIgnoreCase(name);
        }
    }

    private record CookieRule(Pattern pattern, String product, String category) {}

    private record PathRule(String contains, String product, String category) {}

    private record MetaRule(String name, boolean contentIsVersion, Pattern contentPattern, int versionGroup, String requires, String product, String category, String purl, String replaces) {}

    private record PageRule(Pattern pattern, int versionGroup, String product, String category, String purl, String replaces) {}

    private record AssetRule(Pattern pathPattern, Pattern versionPattern, String product, String category, String purl) {}

    private record Rules(List<HeaderRule> headers, List<CookieRule> cookies, List<PathRule> paths, List<MetaRule> metas, List<PageRule> pages, List<AssetRule> assets) {
        static Rules load() {
            InputStream in = TechFingerprints.class.getResourceAsStream("/fingerprints/rules.json");
            if (in == null) {
                throw new IllegalStateException("Missing fingerprints/rules.json");
            }
            try (InputStream stream = in; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return new Rules(
                        headerRules(array(root, "headers")),
                        cookieRules(array(root, "cookies")),
                        pathRules(array(root, "paths")),
                        metaRules(array(root, "metas")),
                        pageRules(array(root, "pages")),
                        assetRules(array(root, "assets")));
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read fingerprints/rules.json", ex);
            }
        }

        private static JsonArray array(JsonObject root, String name) {
            if (!root.has(name) || !root.get(name).isJsonArray()) {
                return new JsonArray();
            }
            return root.getAsJsonArray(name);
        }

        private static List<HeaderRule> headerRules(JsonArray array) {
            List<HeaderRule> rules = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                rules.add(new HeaderRule(
                        text(obj, "header"),
                        pattern(obj, "headerPattern"),
                        pattern(obj, "pattern"),
                        number(obj, "versionGroup"),
                        text(obj, "product"),
                        text(obj, "category"),
                        text(obj, "purl"),
                        text(obj, "replaces")));
            }
            return List.copyOf(rules);
        }

        private static List<CookieRule> cookieRules(JsonArray array) {
            List<CookieRule> rules = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                rules.add(new CookieRule(requiredPattern(obj, "pattern"), text(obj, "product"), text(obj, "category")));
            }
            return List.copyOf(rules);
        }

        private static List<PathRule> pathRules(JsonArray array) {
            List<PathRule> rules = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                rules.add(new PathRule(text(obj, "contains").toLowerCase(Locale.ROOT), text(obj, "product"), text(obj, "category")));
            }
            return List.copyOf(rules);
        }

        private static List<MetaRule> metaRules(JsonArray array) {
            List<MetaRule> rules = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                rules.add(new MetaRule(
                        text(obj, "name"),
                        flag(obj, "contentIsVersion"),
                        pattern(obj, "contentPattern"),
                        number(obj, "versionGroup"),
                        text(obj, "requires"),
                        text(obj, "product"),
                        text(obj, "category"),
                        text(obj, "purl"),
                        text(obj, "replaces")));
            }
            return List.copyOf(rules);
        }

        private static List<PageRule> pageRules(JsonArray array) {
            List<PageRule> rules = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                rules.add(new PageRule(
                        requiredPattern(obj, "pattern"),
                        number(obj, "versionGroup"),
                        text(obj, "product"),
                        text(obj, "category"),
                        text(obj, "purl"),
                        text(obj, "replaces")));
            }
            return List.copyOf(rules);
        }

        private static List<AssetRule> assetRules(JsonArray array) {
            List<AssetRule> rules = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                rules.add(new AssetRule(
                        requiredPattern(obj, "pathPattern"),
                        pattern(obj, "versionPattern"),
                        text(obj, "product"),
                        text(obj, "category"),
                        text(obj, "purl")));
            }
            return List.copyOf(rules);
        }

        private static Pattern requiredPattern(JsonObject obj, String name) {
            Pattern compiled = pattern(obj, name);
            if (compiled == null) {
                throw new IllegalStateException("Fingerprint rule is missing " + name + " for " + text(obj, "product"));
            }
            return compiled;
        }

        private static Pattern pattern(JsonObject obj, String name) {
            String source = text(obj, name);
            if (source.isEmpty()) {
                return null;
            }
            return Pattern.compile(source);
        }

        private static String text(JsonObject obj, String name) {
            if (!obj.has(name) || obj.get(name).isJsonNull() || !obj.get(name).isJsonPrimitive()) {
                return "";
            }
            return obj.get(name).getAsString();
        }

        private static int number(JsonObject obj, String name) {
            if (!obj.has(name) || obj.get(name).isJsonNull() || !obj.get(name).isJsonPrimitive()) {
                return 0;
            }
            return obj.get(name).getAsInt();
        }

        private static boolean flag(JsonObject obj, String name) {
            return obj.has(name) && obj.get(name).isJsonPrimitive() && obj.get(name).getAsBoolean();
        }
    }
}
