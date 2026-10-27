package com.ghostwire.features;

/** One Repeater audit row. Fields are single-line and length-capped. */
public record AuditEntry(String time, String result, String method, String url, String status, String reason) {

    public AuditEntry {
        time = time == null ? "" : time;
        result = result == null ? "" : result;
        method = method == null ? "" : method;
        url = url == null ? "" : url;
        status = status == null ? "" : status;
        reason = reason == null ? "" : reason;
    }

    public static AuditEntry of(String time, String result, String method, String url, String status, String reason) {
        return new AuditEntry(
                clip(time, 40),
                clip(result, 20),
                clip(method, 20),
                clip(url, 500),
                clip(status, 20),
                clip(reason, 300));
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
