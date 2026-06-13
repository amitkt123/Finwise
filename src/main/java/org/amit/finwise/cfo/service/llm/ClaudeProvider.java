package org.amit.finwise.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
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
            @Value("${cfo.llm.claude.max-tokens:3000}") int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("anthropic-beta", "prompt-caching-2024-07-31")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chatWithHistory(List.of(
                LLMMessage.cachedSystem(systemPrompt),
                LLMMessage.user(userMessage)
        ));
    }

    @Override
    public String chatWithHistory(List<LLMMessage> messages) {
        // Build the system field: if any system message is cacheable, use structured content blocks
        List<Map<String, Object>> systemBlocks = new ArrayList<>();
        List<Map<String, Object>> chatMessages = new ArrayList<>();

        long totalPromptLength = 0;
        int cacheableCount = 0;

        for (LLMMessage m : messages) {
            totalPromptLength += m.content().length();
            if (m.cacheable()) {
                cacheableCount++;
            }

            if ("system".equals(m.role())) {
                // Build a content block for this system message
                Map<String, Object> block = new HashMap<>();
                block.put("type", "text");
                block.put("text", m.content());
                if (m.cacheable()) {
                    block.put("cache_control", Map.of("type", "ephemeral"));
                }
                systemBlocks.add(block);
            } else {
                // Non-system message
                Map<String, Object> msg = new HashMap<>();
                msg.put("role", m.role());

                if (m.cacheable()) {
                    // Use structured content blocks for caching
                    List<Map<String, Object>> contentBlocks = new ArrayList<>();
                    Map<String, Object> block = new HashMap<>();
                    block.put("type", "text");
                    block.put("text", m.content());
                    block.put("cache_control", Map.of("type", "ephemeral"));
                    contentBlocks.add(block);
                    msg.put("content", contentBlocks);
                } else {
                    // Plain string content (backward compatible)
                    msg.put("content", m.content());
                }
                chatMessages.add(msg);
            }
        }

        log.info("Sending request to Claude: model={}, messageCount={}, totalPromptLength={}, maxTokens={}, cacheableMessages={}",
                model, messages.size(), totalPromptLength, maxTokens, cacheableCount);

        for (int i = 0; i < messages.size(); i++) {
            LLMMessage msg = messages.get(i);
            String preview = msg.content().length() > 200
                    ? msg.content().substring(0, 200) + "..."
                    : msg.content();
            log.debug("Claude message[{}] role={} cacheable={}: {}", i, msg.role(), msg.cacheable(), preview);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);

        // System field: if we have blocks, use them; otherwise use empty string
        if (!systemBlocks.isEmpty()) {
            requestBody.put("system", systemBlocks);
        } else {
            requestBody.put("system", "");
        }

        requestBody.put("messages", chatMessages);

        try {
            long startTime = System.currentTimeMillis();
            ClaudeResponse response = restClient.post()
                    .uri("/v1/messages")
                    .body(requestBody)
                    .retrieve()
                    .body(ClaudeResponse.class);

            if (response != null && response.content() != null && !response.content().isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseContent = response.content().getFirst().text();
                log.info("Claude response received: duration={}ms, responseLength={}", duration, responseContent.length());
                log.debug("Claude response preview: {}",
                        responseContent.length() > 200 ? responseContent.substring(0, 200) + "..." : responseContent);
                return responseContent;
            }
            log.warn("Claude returned empty or null response");
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
