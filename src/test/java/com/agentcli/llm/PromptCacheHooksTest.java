package com.agentcli.llm;

import com.agentcli.audit.AuditLlmClient;
import com.agentcli.audit.AuditLog;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.17.0「Prompt Cache 元数据钩子」验证。
 *
 * <p>本版本的 prompt cache 是<b>纯元数据</b>：只提供 {@code supportsPromptCaching()} /
 * {@code promptCacheMode()} 供 /status 与成本报表使用，<b>不</b>注入任何自定义 HTTP 头，
 * 因此本类不断言任何请求头。同时覆盖基类新增的 usage / reasoning 方言解析。
 */
class PromptCacheHooksTest {

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
    void promptCacheModeReturnsVendorSpecificIdentifier() {
        assertEquals(PromptCacheMode.MOONSHOT, new KimiCompatibleClient("k").promptCacheMode());
        assertEquals(PromptCacheMode.STEP, new StepCompatibleClient("k").promptCacheMode());
        assertEquals(PromptCacheMode.GLM, new GlmClient("k").promptCacheMode());
        assertEquals(PromptCacheMode.DEEPSEEK, new DeepSeekClient("k").promptCacheMode());

        assertEquals("moonshot-context-cache", PromptCacheMode.MOONSHOT);
        assertEquals("step-prefix-cache", PromptCacheMode.STEP);
        assertEquals("glm-prompt-cache", PromptCacheMode.GLM);
        assertEquals("automatic-prefix-cache", PromptCacheMode.DEEPSEEK);
        assertEquals("none", PromptCacheMode.NONE);
    }

    @Test
    void supportsPromptCachingMatrix() {
        assertTrue(new KimiCompatibleClient("k").supportsPromptCaching());
        assertTrue(new StepCompatibleClient("k").supportsPromptCaching());
        assertTrue(new GlmClient("k").supportsPromptCaching());
        assertTrue(new DeepSeekClient("k").supportsPromptCaching());

        // 未声明缓存能力的 provider 走接口默认实现
        assertFalse(new GenericOpenAiClient("k", null, null).supportsPromptCaching());
        assertEquals(PromptCacheMode.NONE, new GenericOpenAiClient("k", null, null).promptCacheMode());
        assertFalse(new AgnesClient("k").supportsPromptCaching());
        assertEquals(PromptCacheMode.NONE, new AgnesClient("k").promptCacheMode());
        assertFalse(new XfyunCompatibleClient("k").supportsPromptCaching());
        assertEquals(PromptCacheMode.NONE, new XfyunCompatibleClient("k").promptCacheMode());
    }

    @Test
    void auditDecoratorForwardsPromptCacheMetadata() {
        // 仅验证元数据转发，不触发 chat/落盘，故无需真实 SqliteStore
        AuditLog auditLog = new AuditLog(null);

        LlmClient cachingDelegate = new KimiCompatibleClient("k");
        LlmClient audited = new AuditLlmClient(cachingDelegate, auditLog);
        assertTrue(audited.supportsPromptCaching(), "AuditLlmClient 必须转发 supportsPromptCaching");
        assertEquals(PromptCacheMode.MOONSHOT, audited.promptCacheMode(),
                "AuditLlmClient 必须转发 promptCacheMode，否则 /status 会一律显示 none");

        LlmClient plainDelegate = new GenericOpenAiClient("k", null, null);
        LlmClient auditedPlain = new AuditLlmClient(plainDelegate, auditLog);
        assertFalse(auditedPlain.supportsPromptCaching());
        assertEquals(PromptCacheMode.NONE, auditedPlain.promptCacheMode());
    }

    @Test
    void parsesReasoningDetailsArray() throws IOException {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\","
                        + "\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"step-1 \"},"
                        + "{\"type\":\"reasoning.text\",\"content\":\"step-2\"}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        GenericOpenAiClient client = new GenericOpenAiClient(
                "k", "test-model", server.url("/v1").toString());
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);

        assertEquals("step-1 step-2", response.reasoningContent(),
                "reasoning_details 应逐项取 text，缺失时回退 content 并按序拼接");
        assertEquals("done", response.content());
    }

    @Test
    void parsesInputCacheHitTokensDialects() throws IOException {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"input_cache_hit_tokens\":64}}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        GenericOpenAiClient client = new GenericOpenAiClient("k", "test-model", server.url("/v1").toString());
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals(64, response.cachedInputTokens(), "应识别 input_cache_hit_tokens");
        assertEquals(100, response.inputTokens());

        String nestedSse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}],"
                        + "\"usage\":{\"prompt_tokens\":200,\"completion_tokens\":7,"
                        + "\"input_tokens_details\":{\"cached_tokens\":128}}}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(nestedSse).addHeader("Content-Type", "text/event-stream"));

        LlmClient.ChatResponse nested = client.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals(128, nested.cachedInputTokens(), "应识别 input_tokens_details.cached_tokens");
    }

    @Test
    void keepsLegacyCachedTokenDialects() throws IOException {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"c\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":2,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":32}}}",
                "",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));

        GenericOpenAiClient client = new GenericOpenAiClient("k", "test-model", server.url("/v1").toString());
        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);
        assertEquals(32, response.cachedInputTokens(), "既有 prompt_tokens_details.cached_tokens 解析不得回退");
    }
}
