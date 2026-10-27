package com.ghostwire.llm;

public record ConverseResult(String text, String stopReason, int inputTokens, int outputTokens) {
}
