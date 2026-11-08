package com.example.agentic.config;

import com.example.agentic.llm.FaultInjector;
import com.example.agentic.llm.LLMProvider;
import com.example.agentic.llm.MockLLMProvider;
import com.example.agentic.llm.OpenAILLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmConfig {

    /** Selected by agentic.llm.provider. Replacing it requires no change to agents or orchestration. */
    @Bean
    @Primary
    public LLMProvider llmProvider(AgenticProperties props, FaultInjector injector, ObjectMapper mapper) {
        var llm = props.llm();
        if ("openai".equalsIgnoreCase(llm.provider())) {
            if (!llm.openai().enabled() || llm.openai().apiKey().isBlank()) {
                throw new IllegalStateException(
                        "agentic.llm.provider=openai requires agentic.llm.openai.enabled=true and OPENAI_API_KEY");
            }
            return new OpenAILLMProvider(llm.openai(), mapper);
        }
        var m = llm.mock();
        return new MockLLMProvider("mock-llm", m.delayMs(), m.failureProbability(), m.seed(), false, injector);
    }

    /** Degraded, template-only provider used once the primary provider exhausted its retries. */
    @Bean
    @Qualifier("fallbackLlmProvider")
    public LLMProvider fallbackLlmProvider(FaultInjector injector) {
        return new MockLLMProvider("mock-llm-fallback", 0, 0.0, 0, true, injector);
    }
}