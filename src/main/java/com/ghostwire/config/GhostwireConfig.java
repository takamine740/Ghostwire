package com.ghostwire.config;

import burp.api.montoya.persistence.Preferences;

/**
 * User settings persisted in Burp's per-user preferences (never written into .burp project files).
 */
public final class GhostwireConfig {

    public static final String DEFAULT_REGION = "us-west-2";
    public static final String DEFAULT_MODEL_ID = "";
    public static final int DEFAULT_MAX_TOKENS = 4096;
    public static final int DEFAULT_MAX_INPUT_CHARS = 60_000;
    public static final int DEFAULT_AGENT_MAX_REQUESTS = 5;
    public static final int DEFAULT_AGENT_INTERVAL_MS = 1000;

    private static final String K_API_KEY = "gw.apiKey";
    private static final String K_REGION = "gw.region";
    private static final String K_MODEL_ID = "gw.modelId";
    private static final String K_MAX_TOKENS = "gw.maxTokens";
    private static final String K_PROXY_HOST = "gw.proxyHost";
    private static final String K_PROXY_PORT = "gw.proxyPort";
    private static final String K_REDACT = "gw.redact";
    private static final String K_MAX_INPUT_CHARS = "gw.maxInputChars";
    private static final String K_AGENT_APPROVE = "gw.agentApprove";
    private static final String K_AGENT_MAX = "gw.agentMax";
    private static final String K_AGENT_INTERVAL = "gw.agentIntervalMs";
    private static final String K_PASSIVE = "gw.passive";

    private final Preferences prefs;

    public GhostwireConfig(Preferences prefs) {
        this.prefs = prefs;
    }

    public String apiKey() { return str(K_API_KEY, ""); }
    public String region() { return str(K_REGION, DEFAULT_REGION); }
    public String modelId() { return str(K_MODEL_ID, DEFAULT_MODEL_ID); }
    public int maxTokens() { return integer(K_MAX_TOKENS, DEFAULT_MAX_TOKENS); }
    public String proxyHost() { return str(K_PROXY_HOST, ""); }
    public int proxyPort() { return integer(K_PROXY_PORT, 0); }
    public boolean redactionEnabled() {
        Boolean b = prefs.getBoolean(K_REDACT);
        return b != null && b;
    }
    public int maxInputChars() { return integer(K_MAX_INPUT_CHARS, DEFAULT_MAX_INPUT_CHARS); }

    /** Default on, so a Repeater loop sends nothing until the operator clicks. */
    public boolean agentApprovalRequired() {
        Boolean b = prefs.getBoolean(K_AGENT_APPROVE);
        return b == null || b;
    }

    public int agentMaxRequests() {
        return clamp(integer(K_AGENT_MAX, DEFAULT_AGENT_MAX_REQUESTS), 1, 20);
    }

    public int agentIntervalMs() {
        return clamp(integer(K_AGENT_INTERVAL, DEFAULT_AGENT_INTERVAL_MS), 0, 30_000);
    }

    /** Off until the operator saves the Settings checkbox. A Burp scan then reviews in-scope text responses. */
    public boolean passiveEnabled() {
        Boolean b = prefs.getBoolean(K_PASSIVE);
        return b != null && b;
    }

    public void setApiKey(String v) { prefs.setString(K_API_KEY, v.trim()); }
    public void setRegion(String v) { prefs.setString(K_REGION, v.trim()); }
    public void setModelId(String v) { prefs.setString(K_MODEL_ID, v.trim()); }
    public void setMaxTokens(int v) { prefs.setInteger(K_MAX_TOKENS, v); }
    public void setProxyHost(String v) { prefs.setString(K_PROXY_HOST, v.trim()); }
    public void setProxyPort(int v) { prefs.setInteger(K_PROXY_PORT, v); }
    public void setRedactionEnabled(boolean v) { prefs.setBoolean(K_REDACT, v); }
    public void setMaxInputChars(int v) { prefs.setInteger(K_MAX_INPUT_CHARS, v); }
    public void setAgentApprovalRequired(boolean v) { prefs.setBoolean(K_AGENT_APPROVE, v); }
    public void setAgentMaxRequests(int v) { prefs.setInteger(K_AGENT_MAX, v); }
    public void setAgentIntervalMs(int v) { prefs.setInteger(K_AGENT_INTERVAL, v); }
    public void setPassiveEnabled(boolean v) { prefs.setBoolean(K_PASSIVE, v); }

    public String promptOverride(String name) {
        return prefs.getString("gw.prompt." + name);
    }

    public void setPromptOverride(String name, String text) {
        if (text == null) {
            prefs.deleteString("gw.prompt." + name);
        } else {
            prefs.setString("gw.prompt." + name, text);
        }
    }

    private String str(String key, String def) {
        String v = prefs.getString(key);
        return v == null || v.isBlank() ? def : v;
    }

    private int integer(String key, int def) {
        Integer v = prefs.getInteger(key);
        return v == null ? def : v;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
