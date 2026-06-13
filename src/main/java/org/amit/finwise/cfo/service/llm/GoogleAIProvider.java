package org.amit.finwise.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import java.util.List;
import java.util.Map;

@Slf4j
public class GoogleAIProvider implements LLMProvider {

    private final RestClient restClient;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final String apiKey;

    public GoogleAIProvider(String apiKey, String model, float temperature, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Google AI API key must be configured via cfo.llm.google.api-key");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/models")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("x-goog-api-key", apiKey)
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
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                String combinedPrompt = buildPrompt(messages);

                log.info("Sending request to Google AI: model={}, messageCount={}, promptLength={}, temperature={}, maxTokens={}",
                        model, messages.size(), combinedPrompt.length(), temperature, maxTokens);
                log.debug("Google AI prompt preview: {}",
                        combinedPrompt.length() > 200 ? combinedPrompt.substring(0, 200) + "..." : combinedPrompt);

                Map<String, Object> requestBody = Map.of(
                        "contents", List.of(
                                Map.of("parts", List.of(Map.of("text", combinedPrompt)))
                        ),
                        "generationConfig", Map.of(
                                "temperature", temperature,
                                "maxOutputTokens", maxTokens
                        )
                );

                String url = String.format("/%s:generateContent", model);

                long startTime = System.currentTimeMillis();
                GoogleAIResponse response = restClient.post()
                        .uri(url)
                        .body(requestBody)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            String body = "unable to read error response";
                            try {
                                body = res.getBody() != null ? new String(res.getBody().readAllBytes()) : body;
                            } catch (Exception ignored) {}
                            log.error("Google AI API error (HTTP {}): {}", res.getStatusCode(), body);
                            throw new RuntimeException("Google AI returned error: " + res.getStatusCode());
                        })
                        .body(GoogleAIResponse.class);

                long duration = System.currentTimeMillis() - startTime;
                String result = extractText(response);
                log.info("Google AI response received: duration={}ms, responseLength={}", duration, result.length());
                log.debug("Google AI response preview: {}",
                        result.length() > 200 ? result.substring(0, 200) + "..." : result);
                return result;

            } catch (HttpClientErrorException e) {
                retryCount++;
                if (e.getStatusCode().value() == 503 && retryCount < maxRetries) {
                    long backoffMs = 1000L * retryCount;
                    log.warn("Google AI service unavailable (503). Retrying {} of {} after {}ms", retryCount, maxRetries, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying Google AI call", ie);
                    }
                } else {
                    log.error("Google AI HTTP error: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
                    throw new RuntimeException("Google AI LLM call failed: " + e.getMessage(), e);
                }
            } catch (Exception e) {
                log.error("Google AI call failed: type={} message={}", e.getClass().getSimpleName(), e.getMessage());
                throw new RuntimeException("Google AI LLM call failed: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException("Google AI call failed after " + maxRetries + " retries");
    }

    private String buildPrompt(List<LLMMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        for (LLMMessage msg : messages) {
            if ("system".equalsIgnoreCase(msg.role())) {
                prompt.append(msg.content()).append("\n\n");
            } else if ("user".equalsIgnoreCase(msg.role())) {
                prompt.append("User: ").append(msg.content()).append("\n");
            } else if ("assistant".equalsIgnoreCase(msg.role())) {
                prompt.append("Assistant: ").append(msg.content()).append("\n");
            }
        }
        return prompt.toString();
    }

    private String extractText(GoogleAIResponse response) {
        if (response != null && response.candidates() != null && !response.candidates().isEmpty()) {
            var candidate = response.candidates().get(0);
            if (candidate.content() != null && candidate.content().parts() != null && !candidate.content().parts().isEmpty()) {
                return candidate.content().parts().get(0).text();
            }
        }

        if (response != null && response.promptFeedback() != null && response.promptFeedback().blockReason() != null) {
            log.warn("Google AI blocked response due to: {}", response.promptFeedback().blockReason());
        }

        log.warn("Google AI returned empty or null response");
        return "I was unable to generate a response at this time.";
    }

    @Override
    public String providerName() {
        return "google";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleAIResponse(
            @JsonProperty("candidates") List<Candidate> candidates,
            @JsonProperty("promptFeedback") PromptFeedback promptFeedback
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(
            @JsonProperty("content") Content content,
            @JsonProperty("finishReason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(
            @JsonProperty("parts") List<Part> parts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(
            @JsonProperty("text") String text
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(
            @JsonProperty("blockReason") String blockReason
    ) {}
}
