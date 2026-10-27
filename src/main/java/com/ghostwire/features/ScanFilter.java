package com.ghostwire.features;

import com.ghostwire.context.TextBudget;

import java.util.Locale;

/** Which passive-scan responses are worth sending to the model. */
public final class ScanFilter {

    private ScanFilter() {}

    public static boolean textResponse(String contentType, String mimeName) {
        if (TextBudget.isBinary(contentType, mimeName)) {
            return false;
        }
        if (mimeName != null) {
            switch (mimeName) {
                case "HTML", "PLAIN_TEXT", "CSS", "SCRIPT", "JSON", "XML", "YAML", "SSE", "IMAGE_SVG_XML" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        String type = mediaType(contentType);
        if (type.isEmpty()) {
            return false;
        }
        return type.startsWith("text/")
                || type.equals("application/json")
                || type.equals("application/xml")
                || type.equals("application/xhtml+xml")
                || type.equals("application/javascript")
                || type.equals("application/x-javascript")
                || type.equals("application/graphql")
                || type.equals("image/svg+xml")
                || type.endsWith("+json")
                || type.endsWith("+xml");
    }

    private static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        String type = contentType.toLowerCase(Locale.ROOT).trim();
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        return type;
    }
}
