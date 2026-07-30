package com.agentcli.llm;

import com.agentcli.common.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepCompatibleClientTest {

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void usesDefaultModelAndBaseUrl() {
        StepCompatibleClient client = new StepCompatibleClient("test-key");

        assertEquals("step-3.5-flash", client.getModelName());
        assertEquals("step", client.getProviderName());
        assertEquals(256_000, client.maxContextWindow());
        assertEquals("https://api.stepfun.com/v1/chat/completions", client.getApiUrl());
    }

    @Test
    void factoryCreatesStepClientFromAliases() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig pc = new AppConfig.ProviderConfig();
        pc.setApiKey("test-key");
        config.getProviders().put("step", pc);

        assertInstanceOf(StepCompatibleClient.class, LlmClientFactory.create("step", config));
        assertInstanceOf(StepCompatibleClient.class, LlmClientFactory.create("stepfun", config));
        assertInstanceOf(StepCompatibleClient.class, LlmClientFactory.create("step-fun", config));
    }

    @Test
    void injectsReasoningFormatAndEffortIntoRequestBody() throws Exception {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        StepCompatibleClient client = new StepCompatibleClient(
                "test-key", "step-3.5-flash-2603", server.url("/v1/chat/completions").toString());
        client.chat(List.of(LlmClient.Message.user("hi")), null);

        RecordedRequest recorded = server.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertEquals("deepseek-style", body.path("reasoning_format").asText());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertEquals("step-3.5-flash-2603", body.path("model").asText());
    }

    @Test
    void injectsReasoningFormatWithoutEffortForNon2603() throws Exception {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        StepCompatibleClient client = new StepCompatibleClient(
                "test-key", "step-3.5-flash", server.url("/v1/chat/completions").toString());
        client.chat(List.of(LlmClient.Message.user("hi")), null);

        RecordedRequest recorded = server.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertEquals("deepseek-style", body.path("reasoning_format").asText());
        assertTrue(body.path("reasoning_effort").isMissingNode() || body.path("reasoning_effort").isNull(),
                "non-2603 模型不应注入 reasoning_effort，实际: " + body.path("reasoning_effort"));
    }
}
