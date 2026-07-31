package com.agentcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.17.0 Agnes 客户端验证。
 *
 * <p>Agnes 与 OpenAI 协议完全兼容，因此重点验证「薄子类」的元数据正确性
 * （端点拼接 / 默认模型 / Bearer / 窗口 / 工具能力）与基类通道未被破坏（内容 + 工具调用解析）。
 */
class AgnesClientTest {

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
    void defaultsToOfficialEndpointAndModel() {
        AgnesClient client = new AgnesClient("test-key");

        assertEquals("agnes", client.getProviderName());
        assertEquals("agnes-2.5-flash", client.getModelName());
        assertEquals("https://apihub.agnes-ai.com/v1/chat/completions", client.getApiUrl());
        assertEquals(1_000_000, client.maxContextWindow());
        assertTrue(client.supportsTools());
    }

    @Test
    void honoursCustomModelAndBaseUrl() {
        AgnesClient client = new AgnesClient("test-key", "agnes-2.5-pro", "https://gw.example.com/v1/");

        assertEquals("agnes-2.5-pro", client.getModelName());
        assertEquals("https://gw.example.com/v1/chat/completions", client.getApiUrl());

        AgnesClient explicit = new AgnesClient("test-key", null, "https://gw.example.com/v1/chat/completions");
        assertEquals("https://gw.example.com/v1/chat/completions", explicit.getApiUrl());
        assertEquals("agnes-2.5-flash", explicit.getModelName(), "空模型应回退默认模型");
    }

    @Test
    void sendsBearerAuthAndToolsInRequestBody() throws Exception {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        AgnesClient client = new AgnesClient("test-key", "agnes-2.5-flash", server.url("/v1").toString());
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        client.chat(List.of(LlmClient.Message.user("hi")),
                List.of(new LlmClient.Tool("read_file", "读取文件", params)));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));

        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertEquals("agnes-2.5-flash", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean(false));
        JsonNode tools = body.path("tools");
        assertTrue(tools.isArray() && tools.size() == 1, "应下发 tools: " + body);
        assertEquals("read_file", tools.get(0).path("function").path("name").asText());
    }

    @Test
    void parsesStreamedContentAndToolCalls() throws IOException {
        String contentSse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\" Agnes\"}}],"
                        + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(contentSse).addHeader("Content-Type", "text/event-stream"));

        AgnesClient client = new AgnesClient("test-key", "agnes-2.5-flash", server.url("/v1").toString());
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals("Hello Agnes", response.content());
        assertEquals(9, response.inputTokens());
        assertEquals(4, response.outputTokens());

        String toolSse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\","
                        + "\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\".\\\"}\"}}]}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(toolSse).addHeader("Content-Type", "text/event-stream"));

        LlmClient.ChatResponse toolResponse = client.chat(List.of(LlmClient.Message.user("ls")), null);
        assertNotNull(toolResponse.toolCalls());
        assertEquals(1, toolResponse.toolCalls().size());
        assertEquals("call_a", toolResponse.toolCalls().get(0).id());
        assertEquals("list_dir", toolResponse.toolCalls().get(0).function().name());
        assertEquals("{\"path\":\".\"}", toolResponse.toolCalls().get(0).function().arguments());
    }
}
