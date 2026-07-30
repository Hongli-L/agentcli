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

/**
 * 针对 v0.15.0「多厂商 LLM 扩展（Kimi / Step）」的补充验证。
 *
 * <p>工程师已覆盖：默认 model/baseUrl、精确小写别名经 factory 创建、Kimi SSE 流解析、
 * Step 的 reasoning_format / reasoning_effort 注入。本类补充覆盖工程师未触达、但属于
 * 本版本规格的边界：
 * <ul>
 *   <li>alias 归一化的健壮性（大小写 / 首尾空格）—— 验证「从 openai 组剥离」后归一仍正确；</li>
 *   <li>{@code LlmClientFactory.knownProviders()} 含 kimi/step（公开、不受环境变量影响的确认）；</li>
 *   <li>Kimi 的 {@code shouldSendReasoningContentInRequestHistory=true} 钩子：多轮请求历史中
 *       回带上一轮 assistant 的 reasoning_content（仅接收侧在 KimiCompatibleClientTest 已覆盖）。</li>
 * </ul>
 */
class KimiStepExtensionSupplementTest {

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

    private static AppConfig configWith(String provider, String apiKey) {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig pc = new AppConfig.ProviderConfig();
        pc.setApiKey(apiKey);
        config.getProviders().put(provider, pc);
        return config;
    }

    @Test
    void aliasNormalizationIsCaseInsensitiveAndTrimming() {
        // kimi / moonshot 别名组：大小写 + 首尾空格都应归一到 kimi
        AppConfig kimiCfg = configWith("kimi", "test-key");
        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create("KIMI", kimiCfg));
        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create(" Moonshot ", kimiCfg));
        assertInstanceOf(KimiCompatibleClient.class, LlmClientFactory.create("MOONSHOT-AI", kimiCfg));

        // step / stepfun 别名组
        AppConfig stepCfg = configWith("step", "test-key");
        assertInstanceOf(StepCompatibleClient.class, LlmClientFactory.create("StepFun", stepCfg));
        assertInstanceOf(StepCompatibleClient.class, LlmClientFactory.create("STEP-FUN", stepCfg));

        // openai 通用组：大小写归一仍落到 GenericOpenAiClient（确认未被 kimi/step 剥离影响）
        AppConfig oaiCfg = configWith("openai", "test-key");
        assertInstanceOf(GenericOpenAiClient.class, LlmClientFactory.create("OpenAI", oaiCfg));
        assertInstanceOf(GenericOpenAiClient.class, LlmClientFactory.create("OAI", oaiCfg));
    }

    @Test
    void knownProvidersIncludesKimiAndStep() {
        List<String> known = LlmClientFactory.knownProviders();
        assertTrue(known.contains("glm"), "knownProviders 应含 glm: " + known);
        assertTrue(known.contains("deepseek"), "knownProviders 应含 deepseek: " + known);
        assertTrue(known.contains("kimi"), "knownProviders 应含 kimi（v0.15 新增）: " + known);
        assertTrue(known.contains("step"), "knownProviders 应含 step（v0.15 新增）: " + known);
        assertTrue(known.contains("openai"), "knownProviders 应含 openai: " + known);
    }

    @Test
    void kimiEchoesReasoningContentInRequestHistory() throws Exception {
        // 续轮场景：上一轮 assistant 消息带 reasoning_content，Kimi 应在请求体里回带它
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        KimiCompatibleClient client = new KimiCompatibleClient(
                "test-key", "kimi-k2.6", server.url("/v1/chat/completions").toString());
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.user("hi"),
                LlmClient.Message.assistant("I think step by step", "Hello"));

        client.chat(history, null);

        RecordedRequest recorded = server.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        JsonNode messages = body.path("messages");
        assertTrue(messages.isArray() && messages.size() == 2, "应有 2 条历史消息: " + messages);

        JsonNode assistantMsg = messages.get(1);
        assertTrue("assistant".equals(assistantMsg.path("role").asText()), "第二条应为 assistant: " + assistantMsg);
        assertTrue(assistantMsg.has("reasoning_content"),
                "Kimi 续轮应回带 reasoning_content（shouldSendReasoningContentInRequestHistory=true）: " + assistantMsg);
        assertEquals("I think step by step", assistantMsg.path("reasoning_content").asText(),
                "回带的 reasoning_content 值应正确");
        assertEquals("Hello", assistantMsg.path("content").asText());
    }
}
