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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.17.0 讯飞（Xfyun）客户端验证。
 *
 * <p>覆盖：默认端点/模型、双形态（星火开放平台 / MaaS）差异化请求体、lora_id 头、
 * 工具能力白名单与环境变量覆盖、上下文窗口映射、业务码错误识别与 tool_calls 分片聚合。
 */
class XfyunCompatibleClientTest {

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        System.clearProperty(XfyunCompatibleClient.SUPPORTS_TOOLS_ENV);
    }

    @AfterEach
    void tearDown() throws IOException {
        System.clearProperty(XfyunCompatibleClient.SUPPORTS_TOOLS_ENV);
        server.shutdown();
    }

    private static String okSse() {
        return String.join("\n",
                "data: {\"code\":0,\"message\":\"Success\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}",
                "",
                "data: [DONE]",
                "");
    }

    private static LlmClient.Tool sampleTool(ObjectMapper mapper) {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        return new LlmClient.Tool("read_file", "读取文件", params);
    }

    @Test
    void defaultsToSparkOpenPlatformEndpointAndModel() {
        XfyunCompatibleClient client = new XfyunCompatibleClient("test-key");

        assertEquals("xfyun", client.getProviderName());
        assertEquals("4.0Ultra", client.getModelName());
        assertEquals("https://spark-api-open.xf-yun.com/v1/chat/completions", client.getApiUrl());
        assertEquals(XfyunCompatibleClient.Flavor.SPARK_OPEN, client.flavor());
        assertNull(client.loraId());
    }

    @Test
    void detectsMaasFlavorFromBaseUrlHost() {
        XfyunCompatibleClient maas = new XfyunCompatibleClient(
                "test-key", "Qwen3.6-35B-A3B", "https://maas-api.cn-huabei-1.xf-yun.com/v2");

        assertEquals(XfyunCompatibleClient.Flavor.MAAS, maas.flavor());
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions", maas.getApiUrl());
        assertEquals(XfyunCompatibleClient.Flavor.SPARK_OPEN,
                new XfyunCompatibleClient("k", "4.0Ultra", "https://spark-api-open.xf-yun.com/v1").flavor());
    }

    @Test
    void supportsToolsFollowsModelWhitelist() {
        assertTrue(new XfyunCompatibleClient("k", "4.0Ultra").supportsTools());
        assertTrue(new XfyunCompatibleClient("k", "4.0ultra").supportsTools(), "白名单应大小写不敏感");
        assertTrue(new XfyunCompatibleClient("k", "generalv3.5").supportsTools());
        assertTrue(new XfyunCompatibleClient("k", "max-32k").supportsTools());

        // MaaS 自训模型不在白名单 -> 不进工具循环
        assertFalse(new XfyunCompatibleClient("k", "Qwen3.6-35B-A3B",
                "https://maas-api.cn-huabei-1.xf-yun.com/v2").supportsTools());
        assertFalse(new XfyunCompatibleClient("k", "lite").supportsTools());
    }

    @Test
    void supportsToolsCanBeForcedByEnvOverride() {
        System.setProperty(XfyunCompatibleClient.SUPPORTS_TOOLS_ENV, "true");
        assertTrue(new XfyunCompatibleClient("k", "Qwen3.6-35B-A3B",
                "https://maas-api.cn-huabei-1.xf-yun.com/v2").supportsTools(), "非空且非 false 应强制开启");

        System.setProperty(XfyunCompatibleClient.SUPPORTS_TOOLS_ENV, "false");
        assertFalse(new XfyunCompatibleClient("k", "4.0Ultra").supportsTools(), "显式 false 应强制关闭");
    }

    @Test
    void maxContextWindowMapsKnownModels() {
        assertEquals(32_768, new XfyunCompatibleClient("k", "4.0Ultra").maxContextWindow());
        assertEquals(32_768, new XfyunCompatibleClient("k", "max-32k").maxContextWindow());
        assertEquals(131_072, new XfyunCompatibleClient("k", "pro-128k").maxContextWindow());
        assertEquals(8_192, new XfyunCompatibleClient("k", "lite").maxContextWindow());
        assertEquals(8_192, new XfyunCompatibleClient("k", "generalv3").maxContextWindow());
        assertEquals(32_768, new XfyunCompatibleClient("k", "Qwen3.6-35B-A3B",
                "https://maas-api.cn-huabei-1.xf-yun.com/v2").maxContextWindow(), "未知模型回退 32K");
    }

    @Test
    void sparkOpenRequestEnablesToolCallsSwitch() throws Exception {
        server.enqueue(new MockResponse().setBody(okSse()).addHeader("Content-Type", "text/event-stream"));

        // MockWebServer host 为 localhost，不含 maas-api -> SPARK_OPEN
        XfyunCompatibleClient client = new XfyunCompatibleClient(
                "test-key", "4.0Ultra", server.url("/v1").toString());
        client.chat(List.of(LlmClient.Message.user("hi")), List.of(sampleTool(mapper)));

        RecordedRequest recorded = server.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertTrue(body.path("tool_calls_switch").asBoolean(false),
                "星火开放平台且模型支持工具时必须注入 tool_calls_switch=true: " + body);
        assertTrue(body.path("tools").isArray() && !body.path("tools").isEmpty(), "tools 应正常下发: " + body);
        assertFalse(body.has("stream_options"), "星火开放平台形态不应注入 stream_options: " + body);
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        assertNull(recorded.getHeader("lora_id"), "未配置 loraId 时不应出现 lora_id 头");
    }

    @Test
    void sparkOpenOmitsToolCallsSwitchWhenModelUnsupported() throws Exception {
        server.enqueue(new MockResponse().setBody(okSse()).addHeader("Content-Type", "text/event-stream"));

        XfyunCompatibleClient client = new XfyunCompatibleClient(
                "test-key", "lite", server.url("/v1").toString());
        client.chat(List.of(LlmClient.Message.user("hi")), null);

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(body.has("tool_calls_switch"), "模型不支持工具时不应注入开关: " + body);
    }

    @Test
    void loraIdHeaderPresentOnlyWhenConfigured() throws Exception {
        server.enqueue(new MockResponse().setBody(okSse()).addHeader("Content-Type", "text/event-stream"));

        XfyunCompatibleClient withLora = new XfyunCompatibleClient(
                "test-key", "4.0Ultra", server.url("/v1").toString(), "lora-123");
        withLora.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals("lora-123", server.takeRequest().getHeader("lora_id"));

        server.enqueue(new MockResponse().setBody(okSse()).addHeader("Content-Type", "text/event-stream"));
        XfyunCompatibleClient blankLora = new XfyunCompatibleClient(
                "test-key", "4.0Ultra", server.url("/v1").toString(), "   ");
        blankLora.chat(List.of(LlmClient.Message.user("hi")), null);
        assertNull(server.takeRequest().getHeader("lora_id"), "空白 loraId 应视为未配置");
    }

    @Test
    void maasFlavorInjectsStreamOptionsIncludeUsage() {
        XfyunCompatibleClient maas = new XfyunCompatibleClient(
                "test-key", "Qwen3.6-35B-A3B", "https://maas-api.cn-huabei-1.xf-yun.com/v2");

        ObjectNode body = mapper.createObjectNode();
        maas.customizeRequestBody(body);

        assertTrue(body.path("stream_options").path("include_usage").asBoolean(false),
                "MaaS 形态必须显式请求 usage: " + body);
        assertFalse(body.has("tool_calls_switch"), "MaaS 形态不应注入 tool_calls_switch: " + body);
    }

    @Test
    void businessErrorCodeInStreamRaisesIoException() {
        String sse = String.join("\n",
                "data: {\"code\":10007,\"message\":\"user traffic limit\"}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        XfyunCompatibleClient client = new XfyunCompatibleClient(
                "test-key", "4.0Ultra", server.url("/v1").toString());

        IOException error = assertThrows(IOException.class,
                () -> client.chat(List.of(LlmClient.Message.user("hi")), null));
        assertTrue(error.getMessage().contains("10007"), "异常信息应含业务码: " + error.getMessage());
        assertTrue(error.getMessage().contains("user traffic limit"), error.getMessage());
    }

    @Test
    void mergesToolCallDeltasWithSuccessCode() throws IOException {
        String sse = String.join("\n",
                "data: {\"code\":0,\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":"
                        + "[{\"index\":0,\"id\":\"call_x\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                "",
                "data: {\"code\":0,\"choices\":[{\"delta\":{\"tool_calls\":"
                        + "[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        XfyunCompatibleClient client = new XfyunCompatibleClient(
                "test-key", "4.0Ultra", server.url("/v1").toString());
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);

        assertNotNull(response.toolCalls());
        assertEquals(1, response.toolCalls().size());
        LlmClient.ToolCall call = response.toolCalls().get(0);
        assertEquals("call_x", call.id());
        assertEquals("read_file", call.function().name());
        assertEquals("{\"path\":\"a.txt\"}", call.function().arguments());
    }
}
