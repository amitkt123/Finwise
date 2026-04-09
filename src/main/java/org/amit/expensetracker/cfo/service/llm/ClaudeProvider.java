package org.amit.expensetracker.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
public class ClaudeProvider implements LLMProvider {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public ClaudeProvider(
            @Value("${cfo.llm.claude.api-key}") String apiKey,
            @Value("${cfo.llm.claude.model}") String model,
            @Value("${cfo.llm.ollama.max-tokens:3000}") int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chatWithHistory(List.of(
                LLMMessage.system(systemPrompt),
                LLMMessage.user(userMessage)
        ));
    }

    @Override
    public String chatWithHistory(List<LLMMessage> messages) {
        String systemPrompt = messages.stream()
                .filter(m -> "system".equals(m.role()))
                .map(LLMMessage::content)
                .findFirst()
                .orElse("");

        List<Map<String, String>> chatMessages = messages.stream()
                .filter(m -> !"system".equals(m.role()))
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", chatMessages
        );

        try {
            ClaudeResponse response = restClient.post()
                    .uri("/v1/messages")
                    .body(requestBody)
                    .retrieve()
                    .body(ClaudeResponse.class);

            if (response != null && response.content() != null && !response.content().isEmpty()) {
                return response.content().getFirst().text();
            }
            return "I was unable to generate a response at this time.";
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return "claude";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClaudeResponse(@JsonProperty("content") List<ContentBlock> content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(@JsonProperty("text") String text) {}
}
