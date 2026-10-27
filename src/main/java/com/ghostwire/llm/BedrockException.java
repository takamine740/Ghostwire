package com.ghostwire.llm;

public final class BedrockException extends Exception {
    private final int statusCode;

    public BedrockException(String message) {
        this(message, 0, null);
    }

    public BedrockException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public BedrockException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
