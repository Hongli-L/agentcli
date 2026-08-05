package com.agentcli.llm;

import com.agentcli.agent.concurrent.CancellationScope;
import com.agentcli.agent.concurrent.CancellationToken;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消传播第三层（L3 句柄级取消）在真实 HTTP 栈上的行为验证。
 *
 * <p><b>为什么必须有这一层</b>：常见说法是「{@code Thread.interrupt()} 唤不醒阻塞在 socket 上的读」，
 * 但这在 JDK 13+ 已<b>不成立</b>（{@code NioSocketImpl} 取代 {@code PlainSocketImpl} 之后，
 * 中断会让阻塞中的读抛 {@link java.io.InterruptedIOException}）。
 * 本类的 {@link #interruptAbortsReadButYieldsAnAmbiguousError} 就是这条修正结论的可执行证据。
 *
 * <p>所以 L3 的真正价值是<b>语义可识别性</b>而非「唯一能中止」：中断给出的
 * {@code InterruptedIOException} 与真实网络故障无从区分，只有 {@code Call.cancel()} 能让上层
 * 确定性地渲染成「已取消」。{@link #cancelDuringSlowStreamAbortsImmediately} 与前者构成对照组。
 *
 * <p>用 {@link MockWebServer#enqueue} + {@code throttleBody} 造一个「慢 SSE」来复现
 * 「模型正在缓慢吐字，用户按下 /cancel」的真实场景，全程不用 {@code Thread.sleep} 断言时序。
 */
class LlmCancellationTest {

    /** 慢 SSE 的每段字节数与间隔：足以让取消发生在流的中途。 */
    private static final int THROTTLE_BYTES = 8;
    private static final long THROTTLE_PERIOD_MS = 120L;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        CancellationScope.unbind();
        server.shutdown();
    }

    private static String sseBody() {
        return String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"这\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"是\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"一段很长的回答\"}}]}",
                "",
                "data: [DONE]",
                "");
    }

    private void enqueueSlowSse() {
        server.enqueue(new MockResponse()
                .setBody(sseBody())
                .addHeader("Content-Type", "text/event-stream")
                .throttleBody(THROTTLE_BYTES, THROTTLE_PERIOD_MS, TimeUnit.MILLISECONDS));
    }

    private TestClient client() {
        return new TestClient(server.url("/v1/chat/completions").toString());
    }

    @Test
    void cancelDuringSlowStreamAbortsImmediately() throws Exception {
        enqueueSlowSse();
        CancellationToken token = new CancellationToken();
        TestClient client = client();
        CountDownLatch firstChunk = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Thread caller = new Thread(() -> {
            CancellationScope.bind(token);
            try {
                client.chat(List.of(LlmClient.Message.user("hi")), null,
                        new LlmClient.StreamListener() {
                            @Override
                            public void onContentDelta(String delta) {
                                firstChunk.countDown();
                            }
                        });
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                CancellationScope.unbind();
                finished.countDown();
            }
        }, "llm-cancel-test");
        caller.start();

        assertTrue(firstChunk.await(10, TimeUnit.SECONDS), "应先收到至少一个流式分片，确保取消发生在流中途");
        token.cancel(); // L3：钩子里的 Call.cancel() 立刻关掉底层连接

        assertTrue(finished.await(5, TimeUnit.SECONDS),
                "Call.cancel() 必须立即中断 socket read，而不是等整段慢响应读完");
        caller.join(5_000L);

        Throwable error = thrown.get();
        assertNotNull(error, "取消应以 IOException 形式冒泡");
        assertTrue(error instanceof IOException);
        assertEquals("已取消", error.getMessage(),
                "取消要转成统一文案，避免上层报成 'LLM 调用失败' 污染用户视野");
    }

    @Test
    void interruptAbortsReadButYieldsAnAmbiguousError() throws Exception {
        // 这条用例记录的是 L2 的<b>真实边界</b>，而不是常见的以讹传讹。
        //
        // 常见说法是「中断唤不醒阻塞在 socket read 上的线程」。在 JDK 12 及以前确实如此，
        // 但 JDK 13 起 java.net.Socket 底层换成了 NioSocketImpl，阻塞读<b>会</b>被中断唤醒
        // （代价是连接被直接关掉）。本用例实测确认了这一点——所以不能靠「中断没用」来论证 L3。
        //
        // L3 真正不可替代的地方在于<b>语义可识别性</b>：中断只给出一个
        // InterruptedIOException，它和「网络真的抖了」长得一模一样，上层只能报成
        // 「LLM 调用失败」；而 L1 令牌 + L3 Call.cancel() 能把同一个中止明确标注成「已取消」
        // （见 cancelDuringSlowStreamAbortsImmediately）。取消必须是<b>可识别</b>的，
        // 而不只是「让调用停下来」。
        enqueueSlowSse();
        TestClient client = client();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread caller = new Thread(() -> {
            try {
                client.chat(List.of(LlmClient.Message.user("hi")), null,
                        new LlmClient.StreamListener() {
                            @Override
                            public void onContentDelta(String delta) {
                                firstChunk.countDown();
                            }
                        });
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                finished.countDown();
            }
        }, "llm-interrupt-test");
        caller.start();

        assertTrue(firstChunk.await(10, TimeUnit.SECONDS), "应先进入流式读取，中断才落在真正的阻塞点上");
        caller.interrupt(); // 只做 L2，不绑定任何取消令牌

        assertTrue(finished.await(20, TimeUnit.SECONDS));
        caller.join(5_000L);

        Throwable error = thrown.get();
        assertNotNull(error, "JDK 13+ 的 NioSocketImpl 会让中断把阻塞读打断");
        assertTrue(error instanceof InterruptedIOException,
                "得到的是 InterruptedIOException，实际类型: " + error.getClass().getName());
        assertNotEquals("已取消", error.getMessage(),
                "只有 L2 时无法把中止标注为『取消』，上层会误报成 LLM 调用失败");
    }

    @Test
    void hookIsDeregisteredAfterNormalCompletion() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(sseBody())
                .addHeader("Content-Type", "text/event-stream"));
        CancellationToken token = new CancellationToken();
        TestClient client = client();

        CancellationScope.bind(token);
        LlmClient.ChatResponse response;
        try {
            response = client.chat(List.of(LlmClient.Message.user("hi")), null);
        } finally {
            CancellationScope.unbind();
        }

        assertEquals("这是一段很长的回答", response.content());
        assertEquals(0, token.hookCount(),
                "finally 必须注销钩子，否则一次编排数十轮调用会让 CopyOnWriteArrayList 无限增长");
    }

    @Test
    void preCancelledTokenSkipsTheRequestEntirely() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        TestClient client = client();

        CancellationScope.bind(token);
        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> client.chat(List.of(LlmClient.Message.user("hi")), null));
        CancellationScope.unbind();

        assertEquals("已取消", error.getMessage());
        assertEquals(0, server.getRequestCount(), "已取消就不该再浪费一次请求配额");
    }

    @Test
    void unboundScopeLeavesHttpPathUnchanged() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(sseBody())
                .addHeader("Content-Type", "text/event-stream"));
        TestClient client = client();

        LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("hi")), null);

        assertEquals("这是一段很长的回答", response.content());
        assertEquals(1, server.getRequestCount());
        assertNull(CancellationScope.current(), "零侵入：未绑定令牌的既有调用路径完全不受影响");
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
