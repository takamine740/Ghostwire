package com.ghostwire.features;

import java.util.ArrayList;
import java.util.List;

/** Turns a model reply into a short list of Intruder payload values. */
public final class PayloadLines {

    public static final int MAX_PAYLOADS = 30;
    public static final int MAX_LENGTH = 300;

    private PayloadLines() {
    }

    public static List<String> parse(String text) {
        List<String> payloads = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return payloads;
        }
        for (String line : text.split("\\R")) {
            String value = clean(line);
            if (value.isEmpty() || value.length() > MAX_LENGTH || looksLikeProse(value) || payloads.contains(value)) {
                continue;
            }
            payloads.add(value);
            if (payloads.size() >= MAX_PAYLOADS) {
                break;
            }
        }
        return List.copyOf(payloads);
    }

    private static String clean(String line) {
        String value = line.trim();
        if (value.startsWith("```")) {
            return "";
        }
        value = value.replaceFirst("^[-*]\\s+", "");
        value = value.replaceFirst("^\\d+[.)]\\s+", "");
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static boolean looksLikeProse(String value) {
        int spaces = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ' ') {
                spaces++;
            }
        }
        return spaces > 12;
    }
}
