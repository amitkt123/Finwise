package org.amit.finwise.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenRouterProvider implements LLMProvider {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public OpenRouterProvider(String apiKey, String model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));

        this.restClient = RestClient.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .requestFactory(factory)
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
        List<Map<String, String>> msgList = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", msgList,
                "max_tokens", maxTokens
        );

        long totalLen = messages.stream().mapToLong(m -> m.content().length()).sum();
        log.info("Sending request to OpenRouter: model={}, messageCount={}, totalPromptLength={}, maxTokens={}",
                model, messages.size(), totalLen, maxTokens);

        try {
            long start = System.currentTimeMillis();
            OpenRouterResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (_, res) -> {
                        String body = "unable to read error response";
                        try {
                            body = res.getBody() != null ? new String(res.getBody().readAllBytes()) : body;
                        } catch (Exception _) {}
                        log.error("OpenRouter API error (HTTP {}): {}", res.getStatusCode(), body);
                        throw new RuntimeException("OpenRouter returned error: " + res.getStatusCode());
                    })
                    .body(OpenRouterResponse.class);

            if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                long duration = System.currentTimeMillis() - start;
                String content = response.choices().getFirst().message().content();
                log.info("OpenRouter response received: duration={}ms, responseLength={}", duration, content.length());
                log.debug("OpenRouter response preview: {}",
                        content.length() > 200 ? content.substring(0, 200) + "..." : content);
                return content;
            }
            log.warn("OpenRouter returned empty or null response");
            return "I was unable to generate a response at this time.";
        } catch (Exception e) {
            log.error("OpenRouter call failed: type={} message={}", e.getClass().getSimpleName(), e.getMessage());
            throw new RuntimeException("OpenRouter LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return "openrouter";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenRouterResponse(@JsonProperty("choices") List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(@JsonProperty("message") ChoiceMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceMessage(@JsonProperty("content") String content) {}
}
