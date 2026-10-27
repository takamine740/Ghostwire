package com.ghostwire.features;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechFingerprintTest {

    @Test
    void readsAVersionFromTheServerHeaderAndIgnoresTheCookieValue() {
        List<TechHit> hits = TechFingerprints.match(
                "https://www.example.com/",
                List.of(new TechFingerprints.Header("Server", "nginx/1.24.0")),
                List.of("PHPSESSID"),
                "");
        assertEquals(List.of("nginx", "PHP"), hits.stream().map(TechHit::product).toList());
        assertEquals("1.24.0", hits.get(0).version());
        assertEquals("server", hits.get(0).category());
        assertEquals("pkg:generic/nginx@1.24.0", hits.get(0).purl());
        assertEquals("", hits.get(1).version());
        assertEquals("", hits.get(1).purl());
        assertEquals("cookie PHPSESSID", hits.get(1).evidence());
    }

    @Test
    void versionedHeaderReplacesTheBareProductInTheSameResponse() {
        List<TechHit> hits = TechFingerprints.match(
                "https://www.example.com/",
                List.of(new TechFingerprints.Header("X-Powered-By", "PHP/8.2.12")),
                List.of("PHPSESSID"),
                "");
        assertEquals(1, hits.size());
        assertEquals("PHP", hits.get(0).product());
        assertEquals("8.2.12", hits.get(0).version());
        assertEquals("pkg:generic/php@8.2.12", hits.get(0).purl());
    }

    @Test
    void scriptAndPathMarkersCarryOnlyTheirOwnEvidence() {
        String body = """
                <script src="/static/jquery-3.6.0.min.js"></script>
                <p>The blog mentions jquery-9.9.9 in a sentence.</p>
                """;
        List<TechHit> hits = TechFingerprints.match("https://www.example.com/wp-content/themes/style.css?x=1", List.of(), List.of(), body);
        assertTrue(hits.stream().anyMatch(hit -> hit.product().equals("WordPress") && hit.version().isBlank()));
        assertTrue(hits.stream().anyMatch(hit -> hit.product().equals("jQuery") && hit.version().equals("3.6.0")));
        assertTrue(hits.stream().noneMatch(hit -> hit.version().equals("9.9.9")));
    }

    @Test
    void catalogUpgradesABlankVersionAndKeepsASecondOne() {
        List<TechCatalog.Record> records = new ArrayList<>();
        TechCatalog.Record first = TechCatalog.merge(records, new TechHit("jQuery", "", "library", "", "script"), "www.example.com", "Proxy", "https://www.example.com/a", "t1");
        assertEquals("", first.version());
        TechCatalog.Record again = TechCatalog.merge(records, new TechHit("jQuery", "", "library", "", "script"), "www.example.com", "Scanner", "https://www.example.com/b", "t2");
        assertEquals(null, again);
        TechCatalog.Record upgraded = TechCatalog.merge(records, new TechHit("jQuery", "3.6.0", "library", "pkg:npm/jquery@3.6.0", "script"), "www.example.com", "Scanner", "https://www.example.com/c", "t3");
        assertEquals("3.6.0", upgraded.version());
        assertEquals("pkg:npm/jquery@3.6.0", upgraded.purl());
        assertEquals(1, records.size());
        TechCatalog.merge(records, new TechHit("jQuery", "3.7.1", "library", "pkg:npm/jquery@3.7.1", "script"), "www.example.com", "Scanner", "https://www.example.com/d", "t4");
        assertEquals(2, records.size());
        List<TechCatalog.Record> loaded = TechCatalog.fromJson(TechCatalog.toJson(records));
        assertEquals(records, loaded);
        assertTrue(TechCatalog.exportCsv(loaded).contains("\"jQuery\",\"3.6.0\""));
    }

    @Test
    void jiraVersionComesFromThePageAndKeepsCloudFrontAsACdn() {
        String body = """
                <meta name="ajs-version-number" content="9.12.5">
                <script src="https://cdn.example.net/jquery.min.js?ver=3.6.0"></script>
                <script src="/s/com.atlassian.jira.webresources/batch.js"></script>
                <p>The notes mention jquery-9.9.9 and app.8f3a.js.</p>
                """;
        List<TechHit> hits = TechFingerprints.match(
                "https://app.example.com/",
                List.of(new TechFingerprints.Header("x-amz-cf-pop", "SEA73-P2")),
                List.of("atlassian.xsrf.token"),
                body);
        TechHit jira = hits.stream().filter(hit -> hit.product().equals("Jira")).findFirst().orElseThrow();
        assertEquals("9.12.5", jira.version());
        assertEquals("application", jira.category());
        assertEquals("pkg:maven/com.atlassian.jira/jira-core@9.12.5", jira.purl());
        TechHit jquery = hits.stream().filter(hit -> hit.product().equals("jQuery")).findFirst().orElseThrow();
        assertEquals("3.6.0", jquery.version());
        assertEquals("pkg:npm/jquery@3.6.0", jquery.purl());
        assertTrue(jquery.evidence().contains("cdn.example.net"));
        assertTrue(hits.stream().noneMatch(hit -> hit.version().equals("9.9.9") || hit.product().equals("Atlassian")));
        TechHit cloudfront = hits.stream().filter(hit -> hit.product().equals("Amazon CloudFront")).findFirst().orElseThrow();
        assertEquals("cdn", cloudfront.category());
        assertEquals("", cloudfront.version());
        assertEquals("", cloudfront.purl());

        List<TechCatalog.Record> records = new ArrayList<>();
        TechCatalog.Record row = TechCatalog.merge(records, jquery, "app.example.com", "Scanner", "https://app.example.com/", "t1");
        assertEquals("app.example.com", row.host());
        assertTrue(row.evidence().contains("cdn.example.net"));
    }

    @Test
    void largePageKeepsTheVersionTagAndAScriptPastTheOldCap() {
        String pad = "x".repeat(400_000);
        String body = "<meta content=\"9.4.2\" name=\"ajs-version-number\">" + pad
                + "com.atlassian.jira" + pad
                + "<script src=\"https://cdn.example.net/jquery.min.js?ver=3.6.0\"></script>";
        String sample = TechFingerprints.excerpt(body);
        assertTrue(sample.length() < body.length());
        assertTrue(sample.contains("ajs-version-number"));
        assertTrue(sample.contains("com.atlassian.jira"));
        assertTrue(sample.contains("jquery.min.js?ver=3.6.0"));
        List<TechHit> hits = TechFingerprints.match("https://app.example.com/", List.of(), List.of(), sample);
        assertTrue(hits.stream().anyMatch(hit -> hit.product().equals("Jira") && hit.version().equals("9.4.2")));
        assertTrue(hits.stream().anyMatch(hit -> hit.product().equals("jQuery") && hit.version().equals("3.6.0")));
    }

    @Test
    void hashedBundleAndProseDoNotBecomeAProduct() {
        String body = "<script src=\"/static/app.8f3a.js\"></script><p>jquery-9.9.9</p>";
        List<TechHit> hits = TechFingerprints.match("https://www.example.com/", List.of(), List.of(), body);
        assertTrue(hits.isEmpty());
    }

    @Test
    void savedRowsWithoutACategoryStillLoad() {
        String old = "[{\"product\":\"Amazon CloudFront\",\"version\":\"\",\"host\":\"app.example.com\",\"source\":\"Scanner\",\"evidence\":\"x-amz-cf-pop\",\"url\":\"https://app.example.com/\",\"seen\":\"2026-10-26T21:56:23\"}]";
        List<TechCatalog.Record> loaded = TechCatalog.fromJson(old);
        assertEquals(1, loaded.size());
        assertEquals("", loaded.get(0).category());
        assertEquals("", loaded.get(0).purl());
        TechCatalog.Record filled = TechCatalog.merge(loaded, new TechHit("Amazon CloudFront", "", "cdn", "", "header x-amz-cf-pop"),
                "app.example.com", "Scanner", "https://app.example.com/", "t2");
        assertEquals("cdn", filled.category());
        assertEquals(1, loaded.size());
        assertEquals("2026-10-26T21:56:23", filled.seen());
    }
}
