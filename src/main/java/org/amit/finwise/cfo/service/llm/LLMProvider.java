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
     * Provider identifier (e.g. "ollama", "claude", "openai").
     */
    String providerName();
}
