package org.amit.expensetracker.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
public class OllamaProvider implements LLMProvider {

    private final RestClient restClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public OllamaProvider(
            @Value("${cfo.llm.ollama.base-url}") String baseUrl,
            @Value("${cfo.llm.ollama.model}") String model,
            @Value("${cfo.llm.ollama.temperature}") double temperature,
            @Value("${cfo.llm.ollama.max-tokens}") int maxTokens) {
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
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
        List<Map<String, String>> ollamaMessages = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", ollamaMessages,
                "stream", false,
                "options", Map.of(
                        "temperature", temperature,
                        "num_predict", maxTokens
                )
        );

        try {
            OllamaResponse response = restClient.post()
                    .uri("/api/chat")
                    .body(requestBody)
                    .retrieve()
                    .body(OllamaResponse.class);

            if (response != null && response.message() != null) {
                return response.message().content();
            }
            return "I was unable to generate a response at this time.";
        } catch (Exception e) {
            log.error("Ollama call failed: {}", e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OllamaResponse(
            @JsonProperty("message") OllamaMessage message,
            @JsonProperty("done") boolean done
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OllamaMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content
    ) {}
}
