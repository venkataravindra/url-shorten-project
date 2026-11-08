package com.example.agentic.llm;

import com.example.agentic.config.AgenticProperties;
import com.example.agentic.exception.LLMProviderException;
import com.example.agentic.exception.LLMTimeoutException;
import com.example.agentic.exception.ValidationFailureException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional adapter for the OpenAI chat completions API. Disabled by default and NOT exercised by the automated
 * tests (no API key or network is assumed). It asks the model for a JSON object and maps it to the same payload
 * shape the agents validate, so a schema mismatch surfaces as a validation failure rather than silent bad data.
 */
public class OpenAILLMProvider implements LLMProvider {
    private final AgenticProperties.OpenAi config;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OpenAILLMProvider(AgenticProperties.OpenAi config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public String name() { return "openai:" + config.model(); }

    @Override
    public boolean isSimulated() { return false; }

    @Override
    public LLMResponse complete(LLMRequest request) {
        long start = System.nanoTime();
        try {
            String prompt = "Operation: " + request.operation() + "\nRequirement: " + request.requirement()
                    + "\nContext (JSON): " + mapper.writeValueAsString(request.context())
                    + "\nRespond with a single JSON object only, using the same keys a software engineering "
                    + "assistant would return for this operation.";
            Map<String, Object> body = Map.of("model", config.model(),
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(Map.of("role", "system", "content", "You are a careful software engineering assistant."),
                            Map.of("role", "user", "content", prompt)));
            HttpRequest http = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                throw new LLMProviderException("OpenAI returned HTTP " + status);
            }
            if (status >= 400) {
                throw new ValidationFailureException("OpenAI rejected the request with HTTP " + status);
            }
            JsonNode content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            Map<String, Object> payload = mapper.readValue(content.asText(), new TypeReference<>() { });
            return new LLMResponse(request.operation(), name(), false, false, payload,
                    (System.nanoTime() - start) / 1_000_000, "Response from a real LLM; output is still validated by agents.");
        } catch (HttpTimeoutException e) {
            throw new LLMTimeoutException("OpenAI request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMTimeoutException("Interrupted while calling OpenAI");
        } catch (java.io.IOException e) {
            throw new LLMProviderException("OpenAI call failed: " + e.getClass().getSimpleName(), e);
        }
    }
}