package org.amit.finwise.cfo.service.llm;

public record LLMMessage(String role, String content, boolean cacheable) {

    public static LLMMessage system(String content) {
        return new LLMMessage("system", content, false);
    }

    public static LLMMessage user(String content) {
        return new LLMMessage("user", content, false);
    }

    public static LLMMessage assistant(String content) {
        return new LLMMessage("assistant", content, false);
    }

    public static LLMMessage cachedSystem(String content) {
        return new LLMMessage("system", content, true);
    }

    public static LLMMessage cachedUser(String content) {
        return new LLMMessage("user", content, true);
    }
}
