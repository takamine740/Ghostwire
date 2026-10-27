package com.ghostwire.features;

import com.ghostwire.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/** One analysis and its follow-up turns. */
public final class ChatSession {

    private final String title;
    private final String url;
    private final List<ChatMessage> messages = new ArrayList<>();
    private String note;

    public ChatSession(String title, String url) {
        this.title = title;
        this.url = url;
    }

    public String title() {
        return title;
    }

    public String url() {
        return url;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public void add(ChatMessage message) {
        messages.add(message);
    }

    public String note() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    @Override
    public String toString() {
        return title;
    }
}
