package org.amit.finwise.cfo.service.llm;

import java.util.List;

/**
 * Strategy interface for LLM providers.
 * Implementations: OllamaProvider, ClaudeProvider, OpenAIProvider.
 * Active provider is selected via cfo.llm.provider property.
 */
public interface LLMProvider {

    /**
     * Send a single user message with a system prompt.
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * Send a full conversation history (for multi-turn chat).
     */
    String chatWithHistory(List<LLMMessage> messages);

    /**
     * Like {@link #chat} but requests a strict-JSON response where the provider
     * supports it (e.g. Ollama {@code format: json}). Providers without native
     * JSON mode fall back to a normal chat — callers must still parse defensively.
     */
    default String chatJson(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage);
    }

    /**
     * Strict-JSON response constrained to a JSON-schema hint (BPR-3). The schema is
     * a JSON-schema document describing the expected object; it is both appended to
     * the prompt (so every provider sees the contract) and, where the provider has a
     * native JSON/structured-output mode, used to constrain decoding.
     *
     * <p>Default implementation appends the schema to the system prompt and delegates
     * to {@link #chatJson(String, String)} — providers override to wire native modes
     * (Ollama {@code format}, OpenAI {@code response_format}). Callers must still parse
     * defensively and be prepared to fall back: no provider guarantees valid JSON.
     */
    default String chatJson(String systemPrompt, String userMessage, String jsonSchema) {
        return chatJson(withSchema(systemPrompt, jsonSchema), userMessage);
    }

    /** Appends a strict-JSON contract + schema to a system prompt. */
    static String withSchema(String systemPrompt, String jsonSchema) {
        return systemPrompt + "\n\nRespond with ONLY a single valid JSON object — no prose, "
                + "no markdown fences — matching this JSON schema exactly:\n" + jsonSchema;
    }

    /**
     * Provider identifier (e.g. "ollama", "claude", "openai").
     */
    String providerName();
}
