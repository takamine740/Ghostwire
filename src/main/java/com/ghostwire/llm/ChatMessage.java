package com.ghostwire.llm;

/** One turn in a Converse API transcript. System text is sent separately. */
public record ChatMessage(String role, String text) {
    public ChatMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("role must be user or assistant");
        }
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
    }
}
