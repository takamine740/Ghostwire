package com.ghostwire.features;

/**
 * UI side of the Repeater loop. Implementations must not block the Swing thread.
 */
public interface RepeaterControls {

    void setStatus(String text);

    void record(String result, String method, String url, String status, String reason);

    /**
     * Shows the request that would be sent. When {@code waitForApproval} is true, blocks the caller
     * until the operator chooses. Call this off the Swing thread.
     */
    Decision decide(String method, String url, String reason, String rawRequest, boolean waitForApproval)
            throws InterruptedException;

    boolean stopped();
}
