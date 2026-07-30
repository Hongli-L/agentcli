package com.agentcli.llm;

import com.agentcli.common.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class KimiCompatibleClientTest {

    private MockWebServer server;

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
        KimiCompatibleClient client = new KimiCompatibleClient("test-key");

        assertEquals("kimi-k2.6", client.getModelName());
        assertEquals("kimi", client.getProviderName());
        assertEquals(256_000, client.maxContextWindow());
        assertEquals("https://api.moonshot.ai/v1/chat/completions", client.getApiUrl());
    }

    @Test
    void factoryCreatesKimiClientFromAliases() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig pc = new AppConfig.ProviderConfig();
        pc.setApiKey("test-key");
        config.getProviders().put("kimi", pc);

        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create("kimi", config));
        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create("moonshot", config));
        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create("moonshotai", config));
        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create("moonshot-ai", config));
    }

    @Test
    void parsesReasoningContentAndUsageFromStream() throws IOException {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"thinking\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        KimiCompatibleClient client = new KimiCompatibleClient(
                "test-key", "kimi-k2.6", server.url("/v1/chat/completions").toString());
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);

        assertEquals("Hello world", response.content());
        assertEquals("thinking", response.reasoningContent());
        assertEquals(12, response.inputTokens());
        assertEquals(3, response.outputTokens());
    }
}
