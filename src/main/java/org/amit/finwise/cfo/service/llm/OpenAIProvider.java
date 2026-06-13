package org.amit.finwise.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAIProvider implements LLMProvider {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public OpenAIProvider(
            @Value("${cfo.llm.openai.api-key}") String apiKey,
            @Value("${cfo.llm.openai.model}") String model,
            @Value("${cfo.llm.ollama.max-tokens:3000}") int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
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
        List<Map<String, String>> openAiMessages = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        long totalPromptLength = messages.stream().mapToLong(m -> m.content().length()).sum();
        log.info("Sending request to OpenAI: model={}, messageCount={}, totalPromptLength={}, maxTokens={}",
                model, messages.size(), totalPromptLength, maxTokens);

        for (int i = 0; i < messages.size(); i++) {
            LLMMessage msg = messages.get(i);
            String preview = msg.content().length() > 200
                    ? msg.content().substring(0, 200) + "..."
                    : msg.content();
            log.debug("OpenAI message[{}] role={}: {}", i, msg.role(), preview);
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", openAiMessages,
                "max_tokens", maxTokens
        );

        try {
            long startTime = System.currentTimeMillis();
            OpenAIResponse response = restClient.post()
                    .uri("/v1/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(OpenAIResponse.class);

            if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseContent = response.choices().getFirst().message().content();
                log.info("OpenAI response received: duration={}ms, responseLength={}", duration, responseContent.length());
                log.debug("OpenAI response preview: {}",
                        responseContent.length() > 200 ? responseContent.substring(0, 200) + "..." : responseContent);
                return responseContent;
            }
            log.warn("OpenAI returned empty or null response");
            return "I was unable to generate a response at this time.";
        } catch (Exception e) {
            log.error("OpenAI call failed: {}", e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenAIResponse(@JsonProperty("choices") List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(@JsonProperty("message") ChoiceMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceMessage(@JsonProperty("content") String content) {}
}
