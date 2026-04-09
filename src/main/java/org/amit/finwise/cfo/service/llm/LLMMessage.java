package org.amit.finwise.cfo.service.llm;

public record LLMMessage(String role, String content) {

    public static LLMMessage system(String content) {
        return new LLMMessage("system", content);
    }

    public static LLMMessage user(String content) {
        return new LLMMessage("user", content);
    }

    public static LLMMessage assistant(String content) {
        return new LLMMessage("assistant", content);
    }
}
