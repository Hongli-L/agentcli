package com.agentcli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiCompatibleClientStreamingTest {

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

    private TestClient clientFor(String relativePath) {
        return new TestClient(server.url(relativePath).toString());
    }

    @Test
    void parsesReasoningContentAndUsage() throws IOException {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"thinking\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        TestClient client = clientFor("/v1/chat/completions");
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);

        assertEquals("Hello world", response.content());
        assertEquals("thinking", response.reasoningContent());
        assertEquals(12, response.inputTokens());
        assertEquals(3, response.outputTokens());
    }

    @Test
    void mergesToolCallDeltasAcrossChunks() throws IOException {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        TestClient client = clientFor("/v1/chat/completions");
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);

        assertEquals(1, response.toolCalls().size());
        LlmClient.ToolCall call = response.toolCalls().get(0);
        assertNotNull(call);
        assertEquals("call_1", call.id());
        assertEquals("read_file", call.function().name());
        assertEquals("{\"path\":\"a.txt\"}", call.function().arguments());
    }

    private static final class TestClient extends OpenAiCompatibleClient {
        private final String url;

        private TestClient(String url) {
            this.url = url;
        }

        @Override
        protected String getApiUrl() {
            return url;
        }

        @Override
        protected String getModel() {
            return "test-model";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
