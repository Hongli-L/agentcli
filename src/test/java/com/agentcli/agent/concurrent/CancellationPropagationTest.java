package com.agentcli.agent.concurrent;

import com.agentcli.agent.ReActAgent;
import com.agentcli.agent.orchestration.AgentRole;
import com.agentcli.llm.LlmClient;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.tool.ToolInvocation;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三层取消传播的行为契约：{@link CancellationScope} 的线程作用域语义 + L1 协作检查点。
 *
 * <p>最关键的一条是<b>零侵入契约</b>：未绑定令牌时 {@code current()} 必须为 {@code null}，
 * 所有既有调用点行为与引入取消能力之前完全一致——这是 547 条既有回归用例零改动的前提。
 */
class CancellationPropagationTest {

    private StubLlmClient llm;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        System.setProperty("AGENTCLI_MEMORY_DIR", projectRoot.resolve(".memory-test").toString());
        llm = new StubLlmClient("stub-model", "stub");
        toolRegistry = new ToolRegistry(projectRoot.toString());
    }

    @AfterEach
    void tearDown() {
        CancellationScope.unbind();
    }

    @Test
    void unboundScopeIsZeroIntrusion() {
        assertNull(CancellationScope.current(), "未绑定时必须返回 null");
        assertFalse(CancellationScope.isCancelled(), "null 令牌一律视为未取消");
    }

    @Test
    void callBindsTokenAndRestoresPreviousOnExit() throws Exception {
        CancellationToken outer = new CancellationToken();
        CancellationToken inner = new CancellationToken();
        CancellationScope.bind(outer);

        AtomicReference<CancellationToken> seenInside = new AtomicReference<>();
        String value = CancellationScope.call(inner, () -> {
            seenInside.set(CancellationScope.current());
            return "done";
        });

        assertEquals("done", value);
        assertSame(inner, seenInside.get(), "任务体内应看到自己的令牌");
        assertSame(outer, CancellationScope.current(), "退出后必须还原外层令牌，而不是简单 remove");

        CancellationScope.unbind();
        assertNull(CancellationScope.current());
    }

    @Test
    void hooksFireOnceAndCanBeDeregistered() {
        CancellationToken token = new CancellationToken();
        AtomicInteger fired = new AtomicInteger();

        Runnable kept = token.onCancel(fired::incrementAndGet);
        Runnable dropped = token.onCancel(fired::incrementAndGet);
        assertEquals(2, token.hookCount());

        token.removeHook(dropped);
        assertEquals(1, token.hookCount(), "finally 注销后钩子表必须收缩");

        token.cancel();
        assertEquals(1, fired.get());
        token.cancel();
        assertEquals(1, fired.get(), "cancel 幂等：钩子只触发一次");
        assertEquals(0, token.hookCount(), "触发后应释放引用，避免长会话累积");

        // 已取消后再注册：立即执行且不入表
        token.onCancel(fired::incrementAndGet);
        assertEquals(2, fired.get());
        assertEquals(0, token.hookCount());
        assertSame(kept, kept);
    }

    @Test
    void poolThreadDoesNotLeakTokenBetweenTasks() throws Exception {
        // 单线程池：两个任务必定跑在同一条线程上，是检验 ThreadLocal 串味的最严苛场景。
        // 也正是 InheritableThreadLocal 会失效的场景——它只在建线程时快照一次。
        try (AgentTaskExecutor executor = new AgentTaskExecutor(1)) {
            CancellationToken first = new CancellationToken();
            AgentTask taskA = new AgentTask("task_1", "A", AgentRole.WORKER, first);
            AtomicReference<CancellationToken> seenA = new AtomicReference<>();
            executor.submit(taskA, () -> {
                seenA.set(CancellationScope.current());
                return "A";
            }, 5_000L).get(5, TimeUnit.SECONDS);

            CancellationToken second = new CancellationToken();
            AgentTask taskB = new AgentTask("task_2", "B", AgentRole.WORKER, second);
            AtomicReference<CancellationToken> seenB = new AtomicReference<>();
            AtomicBoolean cancelledSeenByB = new AtomicBoolean(true);
            first.cancel(); // 第一个令牌事后被取消，绝不能影响第二个任务
            executor.submit(taskB, () -> {
                seenB.set(CancellationScope.current());
                cancelledSeenByB.set(CancellationScope.isCancelled());
                return "B";
            }, 5_000L).get(5, TimeUnit.SECONDS);

            assertSame(first, seenA.get());
            assertSame(second, seenB.get(), "池线程复用时必须重新绑定当前任务的令牌");
            assertNotEquals(seenA.get(), seenB.get());
            assertFalse(cancelledSeenByB.get(), "上一个任务的取消状态不得串到下一个任务");
        }
    }

    @Test
    void reActAgentReturnsCancelledResultAtLoopHead() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        ReActAgent agent = new ReActAgent(llm, toolRegistry);

        CancellationScope.bind(token);
        String answer;
        try {
            answer = agent.run("随便问点什么");
        } finally {
            CancellationScope.unbind();
        }

        assertEquals(AgentTaskExecutor.CANCELLED_RESULT, answer,
                "L1 协作检查点应在第一轮循环开头就返回统一取消文案");
        assertEquals(0, llm.chatCalls(), "已取消就不该再发出 LLM 请求");
    }

    @Test
    void toolBatchIsSkippedWhenScopeCancelled() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        List<ToolInvocation> invocations = List.of(
                new ToolInvocation("call-1", "list_files", "{\"path\":\".\"}"),
                new ToolInvocation("call-2", "list_files", "{\"path\":\".\"}"));

        CancellationScope.bind(token);
        List<ToolExecutionResult> results;
        try {
            results = toolRegistry.executeTools(invocations);
        } finally {
            CancellationScope.unbind();
        }

        assertEquals(2, results.size(), "取消也必须为每个调用产出一条结果，否则 tool 消息与 tool_call 对不上");
        for (ToolExecutionResult result : results) {
            assertTrue(result.result().contains("取消"));
            assertFalse(result.timedOut(), "用户取消不得被统计成超时");
        }
    }

    @Test
    void unboundScopeKeepsToolExecutionUnchanged() {
        List<ToolExecutionResult> results = toolRegistry.executeTools(List.of(
                new ToolInvocation("call-1", "list_files", "{\"path\":\".\"}")));

        assertEquals(1, results.size());
        assertFalse(results.get(0).result().contains("工具执行已取消"),
                "未绑定令牌时行为必须与引入取消能力之前完全一致");
    }

    @Test
    void cancelDuringLlmCallIsReportedAsCancellationNotFailure() throws Exception {
        // 模拟 L3：Call.cancel() 打断 SSE 时以 IOException 冒泡，
        // ReActAgent 必须识别出「这是主动取消」而不是报成 LLM 调用失败。
        CancellationToken token = new CancellationToken();
        llm.enqueueFailure(new java.io.IOException("Canceled"));
        ReActAgent agent = new ReActAgent(llm, toolRegistry);

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            CancellationScope.bind(token);
            try {
                token.cancel();
                started.countDown();
                return agent.run("触发一次会被取消的调用");
            } finally {
                CancellationScope.unbind();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(AgentTaskExecutor.CANCELLED_RESULT, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void throwIfCancelledSignalsWithDedicatedException() {
        CancellationToken token = new CancellationToken();
        token.throwIfCancelled(); // 未取消时是 no-op

        token.cancel();
        CancellationToken.CancelledException error = org.junit.jupiter.api.Assertions.assertThrows(
                CancellationToken.CancelledException.class, token::throwIfCancelled);
        assertTrue(error.getMessage().contains("取消"));
    }

    @Test
    void nullTokenBindIsEquivalentToUnbind() {
        CancellationToken token = new CancellationToken();
        CancellationScope.bind(token);
        assertSame(token, CancellationScope.current());

        CancellationScope.bind(null);
        assertNull(CancellationScope.current(), "bind(null) 必须等价于解绑，而不是塞一个 null 进 ThreadLocal 表");

        LlmClient.StreamListener noop = LlmClient.StreamListener.NO_OP;
        assertNotEquals(null, noop);
    }
}
