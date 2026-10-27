package com.ghostwire.features;

/** One technology found in a response. Version is empty when the evidence has none. */
public record TechHit(String product, String version, String category, String purl, String evidence) {

    public TechHit {
        product = product == null ? "" : product.trim();
        version = version == null ? "" : version.trim();
        category = category == null ? "" : category.trim();
        purl = purl == null ? "" : purl.trim();
        evidence = evidence == null ? "" : evidence.trim();
    }
}
