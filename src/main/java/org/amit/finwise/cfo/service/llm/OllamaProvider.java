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
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = "unable to read error response";
                        try {
                            body = res.getBody() != null ? new String(res.getBody().readAllBytes()) : body;
                        } catch (Exception ignored) {}
                        log.error("Ollama API error (HTTP {}): {}", res.getStatusCode(), body);
                        throw new RuntimeException("Ollama returned error: " + res.getStatusCode());
                    })
                    .body(OllamaResponse.class);

            if (response != null && response.message() != null && response.message().content() != null) {
                return response.message().content();
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
