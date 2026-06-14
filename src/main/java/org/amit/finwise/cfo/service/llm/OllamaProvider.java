package org.amit.finwise.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
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

        // LLM calls can be very slow (30s–5min for large prompts on a local GPU).
        // Without explicit timeouts the caller thread blocks indefinitely, which
        // starves HikariCP and triggers "Thread starvation or clock leap" warnings.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(180));   // 3 min — generous for local Ollama

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
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
    public String chatJson(String systemPrompt, String userMessage) {
        return chatInternal(List.of(
                LLMMessage.system(systemPrompt),
                LLMMessage.user(userMessage)
        ), true);
    }

    @Override
    public String chatWithHistory(List<LLMMessage> messages) {
        return chatInternal(messages, false);
    }

    /**
     * @param jsonFormat when true, sets Ollama's {@code format: "json"} so the
     *                   model is constrained to emit a single valid JSON value —
     *                   replacing brittle regex recovery on the caller side.
     */
    private String chatInternal(List<LLMMessage> messages, boolean jsonFormat) {
        List<Map<String, String>> ollamaMessages = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> requestBody = new java.util.HashMap<>(Map.of(
                "model", model,
                "messages", ollamaMessages,
                "stream", false,
                "options", Map.of(
                        "temperature", temperature,
                        "num_predict", maxTokens
                )
        ));
        if (jsonFormat) {
            requestBody.put("format", "json");
        }

        long totalPromptLength = messages.stream().mapToLong(m -> m.content().length()).sum();
        log.info("Sending request to Ollama: model={}, messageCount={}, totalPromptLength={}, temperature={}, maxTokens={}",
                model, messages.size(), totalPromptLength, temperature, maxTokens);

        for (int i = 0; i < messages.size(); i++) {
            LLMMessage msg = messages.get(i);
            log.info("Ollama message[{}] role={} (full):\n{}", i, msg.role(), msg.content());
        }

        try {
            long startTime = System.currentTimeMillis();
            OllamaResponse response = restClient.post()
                    .uri("/api/chat")
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (_, res) -> {
                        String body = "unable to read error response";
                        try {
                            body = res.getBody() != null ? new String(res.getBody().readAllBytes()) : body;
                        } catch (Exception _) {}
                        log.error("Ollama API error (HTTP {}): {}", res.getStatusCode(), body);
                        throw new RuntimeException("Ollama returned error: " + res.getStatusCode());
                    })
                    .body(OllamaResponse.class);

            if (response != null && response.message() != null && response.message().content() != null) {
                long duration = System.currentTimeMillis() - startTime;
                String responseContent = response.message().content();
                log.info("Ollama response received: duration={}ms, responseLength={}, done={}",
                        duration, responseContent.length(), response.done());
                log.debug("Ollama response preview: {}",
                        responseContent.length() > 200 ? responseContent.substring(0, 200) + "..." : responseContent);
                return responseContent;
            }
            log.warn("Ollama returned empty or null response");
            return "I was unable to generate a response at this time.";
        } catch (HttpClientErrorException e) {
            log.error("Ollama HTTP error: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Ollama LLM call failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Ollama call failed: type={} message={}", e.getClass().getSimpleName(), e.getMessage());
            throw new RuntimeException("Ollama LLM call failed: " + e.getMessage(), e);
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
