package com.ghostwire.features;

import java.util.Locale;
import java.util.Set;

/** Which HTTP methods the Repeater loop may send, and which always need a click. */
public final class MethodPolicy {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> ALLOWED = Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE", "PATCH");

    private MethodPolicy() {}

    public static boolean allowed(String method) {
        return method != null && ALLOWED.contains(method.trim().toUpperCase(Locale.ROOT));
    }

    /** POST, PUT, DELETE, PATCH, and anything unexpected always wait for approval. */
    public static boolean requiresApproval(String method) {
        if (method == null || method.isBlank()) {
            return true;
        }
        return !SAFE.contains(method.trim().toUpperCase(Locale.ROOT));
    }
}
