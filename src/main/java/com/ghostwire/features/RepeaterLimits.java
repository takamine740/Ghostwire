package com.ghostwire.features;

/** Caps captured when a Repeater loop starts. */
public record RepeaterLimits(int maxRequests, int intervalMillis, boolean approveEach) {
    public RepeaterLimits {
        if (maxRequests < 1) {
            maxRequests = 1;
        } else if (maxRequests > 20) {
            maxRequests = 20;
        }
        if (intervalMillis < 0) {
            intervalMillis = 0;
        } else if (intervalMillis > 30_000) {
            intervalMillis = 30_000;
        }
    }
}
