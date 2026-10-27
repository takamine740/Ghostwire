package com.ghostwire.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Bundled default prompts from {@code /prompts/<name>.txt}, overridable per user via preferences.
 */
public final class Prompts {

    public static final String SYSTEM = "system";
    public static final String ANALYZE = "analyze";
    public static final String INTRUDER = "intruder";
    public static final String REPEATER = "repeater";
    public static final String PASSIVE = "passive";
    public static final String REPORT = "report";

    public static final List<String> NAMES = List.of(SYSTEM, ANALYZE, INTRUDER, REPEATER, PASSIVE, REPORT);

    private final GhostwireConfig config;

    public Prompts(GhostwireConfig config) {
        this.config = config;
    }

    public String get(String name) {
        String override = config.promptOverride(name);
        return override != null && !override.isBlank() ? normalize(override) : bundled(name);
    }

    /** Bundled system prompt with {{SCOPE}} filled in. */
    public String systemPrompt(String scopeDescription) {
        String scope = (scopeDescription == null || scopeDescription.isBlank())
                ? "targets marked in scope in this Burp project"
                : scopeDescription;
        return get(SYSTEM).replace("{{SCOPE}}", scope);
    }

    public boolean isOverridden(String name) {
        String override = config.promptOverride(name);
        return override != null && !override.isBlank();
    }

    public void save(String name, String text) {
        String normalized = normalize(text);
        config.setPromptOverride(name, normalized.equals(bundled(name)) ? null : normalized);
    }

    public void reset(String name) {
        config.setPromptOverride(name, null);
    }

    public static String bundled(String name) {
        try (InputStream in = Prompts.class.getResourceAsStream("/prompts/" + name + ".txt")) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled prompt: " + name);
            }
            return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
