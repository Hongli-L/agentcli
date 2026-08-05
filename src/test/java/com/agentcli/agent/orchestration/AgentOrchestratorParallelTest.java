package com.agentcli.agent.orchestration;

import com.agentcli.agent.concurrent.AgentTaskExecutor;
import com.agentcli.agent.concurrent.AgentTaskState;
import com.agentcli.agent.concurrent.AgentTaskView;
import com.agentcli.testsupport.ConcurrentStubLlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04 并行批编排验收：拓扑分批 + 并发上限 + 部分失败降级 + 一次性复核 + 有序 flush。
 *
 * <p><b>时序纪律</b>：全程不用 {@code Thread.sleep} 做时序假设。需要「捕捉 N 个 worker 同时在跑」
 * 的瞬间时，用 {@link ConcurrentStubLlmClient} 的闸门（{@code CountDownLatch}）精确同步；
 * 需要「确保越过某个绝对时刻」时，用对时钟的忙等——那是确定性的后置条件，不是对调度的猜测。
 */
class AgentOrchestratorParallelTest {

    private static final String GOAL = "梳理支付链路的三处风险点，然后汇总成一份评审材料";

    private static final long AWAIT_MS = 5_000L;
    private static final long JOIN_SECONDS = 30L;

    private ConcurrentStubLlmClient stub;
    private ToolRegistry tools;
    private ByteArrayOutputStream console;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        stub = new ConcurrentStubLlmClient("stub", "stub");
        tools = new ToolRegistry(projectRoot.toString());
        console = new ByteArrayOutputStream();
    }

    // ------------------------------------------------------------------ 并发上限

    @Test
    void concurrencyPeaksAtPoolSizeAndNeverExceedsIt() throws Exception {
        stub.withPlan(independentPlan(5)).withDefaultWorkerAnswer("ok");
        stub.closeGate(3);

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, false, 60_000L))) {
            CompletableFuture<OrchestrationResult> running =
                    CompletableFuture.supplyAsync(() -> orchestrator.run(GOAL));

            assertTrue(stub.awaitWorkerArrivals(3, AWAIT_MS), "并发上限为 3 时应有 3 个 worker 同时抵达闸门");
            assertTrue(stub.activeWorkers() <= 3, "在闸门前取样：同时在跑的 worker 不得超过池尺寸");
            stub.openGate();

            OrchestrationResult result = running.get(JOIN_SECONDS, TimeUnit.SECONDS);

            assertFalse(result.degraded());
            assertEquals(5, result.doneCount(), "5 个任务全部完成");
            assertEquals(3, stub.peakConcurrency(), "并发峰值恰好等于并发上限，多出的任务在队列里等");
            assertEquals(3, orchestrator.workerPool().available(), "worker 全部归还");
        }
    }

    // ------------------------------------------------------------------ 部分失败降级

    @Test
    void partialFailureKeepsSiblingResultsAndFlagsDegradation() throws Exception {
        stub.withPlan(independentPlan(3))
                .withWorkerAnswer("task_1", "风险点一：超时未重试")
                .withRuntimeFailure("task_2")
                .withWorkerAnswer("task_3", "风险点三：缺少幂等键");

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, false, 60_000L))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertTrue(result.degraded(), "存在失败任务时必须标记降级");
            assertEquals(2, result.doneCount(), "单个失败不得掀桌子，兄弟任务的产出要保住");
            assertEquals(1, result.failedCount());

            String summary = result.summaryText();
            assertTrue(summary.contains("✅ task_1"), summary);
            assertTrue(summary.contains("❌ task_2"), summary);
            assertTrue(summary.contains("✅ task_3"), summary);
            assertTrue(summary.contains("风险点一：超时未重试"), summary);
            assertTrue(summary.contains("风险点三：缺少幂等键"), summary);
            assertTrue(summary.contains("完成 2/3"), summary);
        }
    }

    @Test
    void downstreamOfFailedTaskIsSkippedWhileIndependentBranchStillRuns() throws Exception {
        stub.withPlan(fanOutPlan())
                .withRuntimeFailure("task_1")
                .withWorkerAnswer("task_3", "巡检通过");

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, false, 60_000L))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertTrue(result.degraded());
            assertEquals(1, result.doneCount(), "独立分支不受失败分支牵连");
            assertEquals(2, result.tasks().size(), "只有 task_1 / task_3 被派发过");
            assertNull(stub.promptForTask("task_2"), "依赖未满足的下游绝不派发");

            String summary = result.summaryText();
            assertTrue(summary.contains("❌ task_1"), summary);
            assertTrue(summary.contains("⏭ task_2"), summary);
            assertTrue(summary.contains("✅ task_3"), summary);
        }
    }

    // ------------------------------------------------------------------ 复核开关

    @Test
    void reviewerRunsExactlyOnceForTheWholeBatch() throws Exception {
        stub.withPlan(independentPlan(3))
                .withReview("{\"approved\": true, \"issues\": \"\", \"summary\": \"三点风险已覆盖\"}");

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, true, 60_000L))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertEquals(1, stub.reviewerCalls(), "3 个子任务只换来 1 次复核调用（成本 O(1)）");
            assertNotNull(result.reviewVerdict());
            assertTrue(result.reviewVerdict().approved());
            assertTrue(result.render().contains("✅ 复核通过"), result.render());
            assertTrue(result.render().contains("三点风险已覆盖"), result.render());
        }
    }

    @Test
    void reviewerDisabledSkipsTheExtraLlmCallEntirely() throws Exception {
        stub.withPlan(independentPlan(3));

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, false, 60_000L))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertEquals(0, stub.reviewerCalls());
            assertNull(result.reviewVerdict());
            assertEquals(result.summaryText(), result.render(), "无复核时渲染就是聚合正文本身");
            assertEquals(4, stub.chatCalls(), "1 次规划 + 3 次 worker，没有多余调用");
        }
    }

    @Test
    void reviewerFailureDegradesToUnavailableVerdictWithoutBreakingTheRun() throws Exception {
        stub.withPlan(independentPlan(3))
                .withReviewFailure(new IllegalStateException("复核服务 500"));

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, true, 60_000L))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertFalse(result.degraded(), "复核挂掉不改变编排本身的成败");
            assertEquals(3, result.doneCount());
            assertNotNull(result.reviewVerdict());
            assertFalse(result.reviewVerdict().approved(), "复核不可用时保守判不通过");
            assertTrue(result.reviewVerdict().issues().contains(ReviewVerdict.REVIEW_UNAVAILABLE),
                    result.reviewVerdict().issues());
            assertTrue(result.reviewVerdict().issues().contains("复核服务 500"),
                    result.reviewVerdict().issues());
        }
    }

    // ------------------------------------------------------------------ 有序输出

    @Test
    void batchOutputIsFlushedInTopologicalOrderEvenWhenCompletionOrderDiffers() throws Exception {
        // task_1 被闸门卡住 → 它是最后一个完成的；但 flush 必须仍把它排在最前
        stub.withPlan(independentPlan(3)).withDefaultWorkerAnswer("ok").gateOnly("task_1");
        stub.closeGate(1);

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, false, 60_000L))) {
            CompletableFuture<OrchestrationResult> running =
                    CompletableFuture.supplyAsync(() -> orchestrator.run(GOAL));

            assertTrue(stub.awaitWorkerArrivals(1, AWAIT_MS), "task_1 应已抵达闸门并被卡住");
            assertTrue(await(() -> stub.workerCalls() >= 3 && stub.activeWorkers() == 1, AWAIT_MS),
                    "另外两个任务应已跑完，只剩被卡住的 task_1");
            stub.openGate();

            OrchestrationResult result = running.get(JOIN_SECONDS, TimeUnit.SECONDS);
            assertEquals(3, result.doneCount());

            String printed = console.toString(StandardCharsets.UTF_8);
            assertTrue(printed.indexOf("▶ task_1") < printed.indexOf("▶ task_2"), printed);
            assertTrue(printed.indexOf("▶ task_2") < printed.indexOf("▶ task_3"), printed);
        }
    }

    // ------------------------------------------------------------------ 全局预算与取消

    @Test
    void globalBudgetStopsDispatchingFurtherBatches() throws Exception {
        stub.withPlan(chainPlan()).withDefaultWorkerAnswer("ok").gateOnly("task_1");
        stub.closeGate(1);
        long globalBudgetMs = 200L;

        try (AgentOrchestrator orchestrator = newOrchestrator(config(3, false, globalBudgetMs))) {
            long start = System.currentTimeMillis();
            CompletableFuture<OrchestrationResult> running =
                    CompletableFuture.supplyAsync(() -> orchestrator.run(GOAL));

            assertTrue(stub.awaitWorkerArrivals(1, AWAIT_MS));
            // 忙等到确定越过全局预算再放行：对时钟的确定性等待，不是对线程调度的假设
            spinUntil(start + globalBudgetMs + 800L);
            stub.openGate();

            OrchestrationResult result = running.get(JOIN_SECONDS, TimeUnit.SECONDS);

            assertTrue(result.degraded());
            assertEquals(1, result.doneCount(), "第一批照常完成");
            assertEquals(1, stub.workerCalls(), "预算耗尽后不得再派发新批次");
            assertNull(stub.promptForTask("task_2"));
            assertTrue(console.toString(StandardCharsets.UTF_8).contains("已达全局编排预算"),
                    console.toString(StandardCharsets.UTF_8));
            assertTrue(result.summaryText().contains("⏭ task_2"), result.summaryText());
        }
    }

    @Test
    void cancelAllDuringABatchLandsTasksInCancelledNotFailed() throws Exception {
        stub.withPlan(independentPlan(3)).withDefaultWorkerAnswer("ok");
        stub.closeGate(3);

        try (AgentTaskExecutor executor = new AgentTaskExecutor(3);
             AgentOrchestrator orchestrator =
                     new AgentOrchestrator(stub, tools, config(3, false, 60_000L), executor)) {
            orchestrator.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
            CompletableFuture<OrchestrationResult> running =
                    CompletableFuture.supplyAsync(() -> orchestrator.run(GOAL));

            assertTrue(stub.awaitWorkerArrivals(3, AWAIT_MS));
            assertEquals(3, executor.cancelAll(), "共享执行器能看见并取消本轮编排的全部任务");
            stub.openGate();

            OrchestrationResult result = running.get(JOIN_SECONDS, TimeUnit.SECONDS);

            assertTrue(result.degraded());
            assertEquals(0, result.doneCount());
            assertEquals(3, result.tasks().size());
            for (AgentTaskView view : result.tasks()) {
                assertEquals(AgentTaskState.CANCELLED, view.state(),
                        "被取消的任务不能被记成 FAILED：取消不是错误");
            }
            // plan.Task 没有 CANCELLED，落 SKIPPED；完整执行态保留在 AgentTaskView 里
            assertTrue(result.summaryText().contains("⏭ task_1"), result.summaryText());
        }
    }

    // ------------------------------------------------------------------ 辅助

    private AgentOrchestrator newOrchestrator(OrchestrationConfig config) {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stub, tools, config);
        orchestrator.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        return orchestrator;
    }

    private static OrchestrationConfig config(int parallel, boolean reviewer, long globalTimeoutMs) {
        return new OrchestrationConfig(parallel, parallel, 30_000L, globalTimeoutMs, reviewer, 200);
    }

    /** 轮询等待条件成立，替代 sleep 做时序同步。 */
    private static boolean await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    /** 忙等到某个绝对时刻（用于确定性地越过全局预算）。 */
    private static void spinUntil(long absoluteMillis) {
        while (System.currentTimeMillis() < absoluteMillis) {
            Thread.onSpinWait();
        }
    }

    /** {@code count} 个互不依赖的任务，全部落在同一批里并发执行。 */
    private static String independentPlan(int count) {
        StringBuilder sb = new StringBuilder("{\"summary\":\"并行计划\",\"tasks\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(i)
                    .append("\",\"description\":\"独立子任务 ").append(i)
                    .append("\",\"type\":\"ANALYSIS\",\"dependencies\":[]}");
        }
        return sb.append("]}").toString();
    }

    /** {@code task_1 → task_2} 两批链式计划，用于验证「第二批是否还会被派发」。 */
    private static String chainPlan() {
        return """
                {"summary":"两批计划","tasks":[
                  {"id":"1","description":"抓取链路数据","type":"COMMAND","dependencies":[]},
                  {"id":"2","description":"基于数据出评审材料","type":"ANALYSIS","dependencies":["1"]}
                ]}""";
    }

    /** 一个失败分支 + 一个独立分支：验证「牵连范围只到下游，不波及兄弟」。 */
    private static String fanOutPlan() {
        return """
                {"summary":"混合计划","tasks":[
                  {"id":"1","description":"抓取链路数据","type":"COMMAND","dependencies":[]},
                  {"id":"2","description":"基于数据出评审材料","type":"ANALYSIS","dependencies":["1"]},
                  {"id":"3","description":"独立巡检配置","type":"VERIFICATION","dependencies":[]}
                ]}""";
    }
}
