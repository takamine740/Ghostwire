package com.ghostwire.features;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable cache key for one scanned response. */
public final class ResponseHash {

    private ResponseHash() {}

    public static String key(String url, int status, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(url == null ? new byte[0] : url.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(status).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (body != null && body.length > 0) {
                digest.update(body);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}
