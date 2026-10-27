package com.ghostwire.llm;

public interface ClientLog {
    void info(String message);

    void error(String message);
}
