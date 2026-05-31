package org.amit.finwise.cfo.config;

import org.amit.finwise.cfo.service.llm.ClaudeProvider;
import org.amit.finwise.cfo.service.llm.GoogleAIProvider;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.cfo.service.llm.OpenAIProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class LLMConfig {

    @Value("${cfo.llm.provider:ollama}")
    private String provider;

    @Value("${cfo.llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${cfo.llm.ollama.model:deepseek-r1:7b}")
    private String ollamaModel;

    @Value("${cfo.llm.ollama.temperature:0.3}")
    private double ollamaTemperature;

    @Value("${cfo.llm.ollama.max-tokens:3000}")
    private int ollamaMaxTokens;

    @Value("${cfo.llm.claude.api-key:}")
    private String claudeApiKey;

    @Value("${cfo.llm.claude.model:claude-opus-4-6}")
    private String claudeModel;

    @Value("${cfo.llm.openai.api-key:}")
    private String openAiApiKey;

    @Value("${cfo.llm.openai.model:gpt-4o}")
    private String openAiModel;

    @Value("${cfo.llm.google.api-key:}")
    private String googleApiKey;

    @Value("${cfo.llm.google.model:gemini-1.5-flash}")
    private String googleModel;

    @Value("${cfo.llm.google.temperature:0.3}")
    private float googleTemperature;

    @Bean
    public LLMProvider llmProvider() {
        return switch (provider.toLowerCase()) {
            case "claude" -> new ClaudeProvider(claudeApiKey, claudeModel, ollamaMaxTokens);
            case "openai" -> new OpenAIProvider(openAiApiKey, openAiModel, ollamaMaxTokens);
            case "google" -> new GoogleAIProvider(googleApiKey, googleModel, googleTemperature, ollamaMaxTokens);
            default -> throw new IllegalArgumentException("Unknown LLM provider: " + provider);
        };
    }

    /**
     * Dedicated executor for LLM refinement tasks (@Async in LlmRefinementService).
     *
     * Why a dedicated pool instead of Spring's default SimpleAsyncTaskExecutor:
     *  - SimpleAsyncTaskExecutor creates a new thread per task — no queue, no back-pressure.
     *    Under a burst of news (e.g. market open + earnings season), it spawns unbounded
     *    threads that overwhelm Ollama and cause tasks to be silently dropped.
     *  - This bounded pool queues up to 200 review tasks and processes them with up to
     *    2 parallel threads (safe for a local Ollama instance with limited context window).
     *  - CallerRunsPolicy ensures that when the queue is full, the calling thread blocks
     *    instead of dropping work — guaranteeing all articles eventually get reviewed.
     *
     * Bean name "llmRefinementExecutor" matches the @Async("llmRefinementExecutor") annotation.
     */
    @Bean(name = "llmRefinementExecutor")
    @Primary
    public Executor llmRefinementExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);         // 1 steady-state thread — Ollama is single-GPU
        executor.setMaxPoolSize(2);          // burst to 2 max (e.g. manual trigger + scheduler)
        executor.setQueueCapacity(200);      // buffer up to 200 batch jobs before blocking
        executor.setThreadNamePrefix("llm-refine-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
