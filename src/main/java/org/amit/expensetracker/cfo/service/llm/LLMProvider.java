package org.amit.expensetracker.cfo.service.llm;

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
     * Provider identifier (e.g. "ollama", "claude", "openai").
     */
    String providerName();
}
