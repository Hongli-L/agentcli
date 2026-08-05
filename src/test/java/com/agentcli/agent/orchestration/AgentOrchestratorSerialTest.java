package com.agentcli.agent.orchestration;

import com.agentcli.agent.concurrent.AgentTaskState;
import com.agentcli.testsupport.ConcurrentStubLlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03 串行主从编排验收：{@code maxParallel <= 1} 时走 {@code runSerial}。
 *
 * <p>本类只用 {@link ConcurrentStubLlmClient}（按内容路由的确定性桩），不触碰真实 LLM。
 * 串行路径的断言重点有三：<b>拓扑序执行</b>、<b>上下文隔离</b>（下游只见上游结果摘要）、
 * <b>失败中止</b>（上游失败则下游根本不派发，而不是带着脏输入硬跑）。
 */
class AgentOrchestratorSerialTest {

    /** 含「然后」故不会被 {@code Planner#isSimpleGoal} 判为简单目标，必定走 LLM 规划路径。 */
    private static final String GOAL = "重构订单模块并补充单元测试，然后输出一份完整的验收报告";

    private ConcurrentStubLlmClient stub;
    private ToolRegistry tools;
    private ByteArrayOutputStream console;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        stub = new ConcurrentStubLlmClient("stub", "stub");
        tools = new ToolRegistry(projectRoot.toString());
        console = new ByteArrayOutputStream();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void serialRunsEveryTaskInTopologicalOrderAndAggregatesWithoutDegrading() {
        stub.withPlan(chainPlan())
                .withWorkerAnswer("task_1", "配置读取完毕：端口 8080")
                .withWorkerAnswer("task_2", "分析完毕：无异常配置项");

        try (AgentOrchestrator orchestrator = newOrchestrator(serialConfig(500, false))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertFalse(result.degraded(), "两个任务都成功，不应标记降级");
            assertEquals(2, result.doneCount());
            assertEquals(0, result.failedCount());
            assertEquals(1, stub.plannerCalls(), "规划只调用一次");
            assertEquals(2, stub.workerCalls());
            assertEquals(1, stub.peakConcurrency(), "串行路径任何时刻只允许一个 worker 在跑");

            String summary = result.summaryText();
            assertTrue(summary.contains("✅ task_1"), summary);
            assertTrue(summary.contains("✅ task_2"), summary);
            assertTrue(summary.contains("完成 2/2"), summary);
            assertTrue(summary.indexOf("task_1") < summary.indexOf("task_2"), "报告必须按拓扑序排列");

            String printed = console.toString(StandardCharsets.UTF_8);
            assertTrue(printed.contains("🤝 多 Agent 编排启动"), printed);
            assertTrue(printed.indexOf("▶ task_1") < printed.indexOf("▶ task_2"), printed);
        }
    }

    @Test
    void downstreamPromptCarriesOnlyTruncatedUpstreamResultNotItsConversation() {
        String longAnswer = "A".repeat(50) + "TAIL";
        stub.withPlan(chainPlan())
                .withWorkerAnswer("task_1", longAnswer)
                .withWorkerAnswer("task_2", "已完成分析");

        try (AgentOrchestrator orchestrator = newOrchestrator(serialConfig(20, false))) {
            orchestrator.run(GOAL);
        }

        String downstream = stub.promptForTask("task_2");
        assertNotNull(downstream, "下游任务应当被派发过");
        assertTrue(downstream.contains("【前置任务的产出】"), downstream);
        assertTrue(downstream.contains("A".repeat(20)), "依赖结果摘要必须出现在下游 prompt 中");
        assertFalse(downstream.contains("TAIL"), "超出预算的依赖结果必须被截断");
        assertTrue(downstream.contains("已截断至 20 字符"), "截断要显式告知模型，避免它以为原文就这么短");
        // 上下文隔离红线：下游看不到上游 worker 的原始对话（否则 prompt 会逐级膨胀且带上试错噪声）
        assertFalse(downstream.contains("【当前子任务】task_1"), downstream);
    }

    @Test
    void upstreamFailureSkipsDownstreamAndMarksResultDegraded() {
        stub.withPlan(chainPlan()).withRuntimeFailure("task_1");

        try (AgentOrchestrator orchestrator = newOrchestrator(serialConfig(200, false))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertTrue(result.degraded());
            assertEquals(0, result.doneCount());
            assertEquals(1, result.tasks().size(), "task_2 依赖未满足，根本不会被派发");
            assertEquals(AgentTaskState.FAILED, result.tasks().get(0).state());
            assertEquals(1, stub.workerCalls(), "下游不得带着脏输入执行");
            assertNull(stub.promptForTask("task_2"));

            String summary = result.summaryText();
            assertTrue(summary.contains("❌ task_1"), summary);
            assertTrue(summary.contains("worker 故障注入"), summary);
            assertTrue(summary.contains("⏭ task_2"), summary);
            assertTrue(summary.contains("（前置任务未成功，已跳过）"), summary);
            assertTrue(summary.contains("完成 0/2"), summary);
        }
    }

    @Test
    void plannerFailureIsReportedAndNoWorkerIsEverInvoked() {
        stub.withPlanFailure(new IOException("规划服务不可用"));

        try (AgentOrchestrator orchestrator = newOrchestrator(serialConfig(200, false))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertTrue(result.degraded());
            assertTrue(result.tasks().isEmpty());
            assertTrue(result.summaryText().contains("任务规划失败"), result.summaryText());
            assertTrue(result.summaryText().contains("规划服务不可用"), result.summaryText());
            assertEquals(0, stub.workerCalls(), "规划都没成功，不该烧 worker 的 token");
        }
    }

    @Test
    void blankGoalIsRejectedBeforeAnyLlmCall() {
        try (AgentOrchestrator orchestrator = newOrchestrator(serialConfig(200, false))) {
            OrchestrationResult result = orchestrator.run("   ");

            assertTrue(result.degraded());
            assertEquals("编排目标不能为空。", result.summaryText());
            assertEquals(0, stub.chatCalls(), "空目标必须在进入规划前短路");
        }
    }

    @Test
    void poolIsFullyRestoredAndReviewerRunsExactlyOnceInSerialMode() {
        stub.withPlan(chainPlan())
                .withReview("{\"approved\": true, \"issues\": \"\", \"summary\": \"两步都已达成\"}");

        try (AgentOrchestrator orchestrator = newOrchestrator(serialConfig(200, true))) {
            OrchestrationResult result = orchestrator.run(GOAL);

            assertEquals(1, orchestrator.workerPool().size());
            assertEquals(1, orchestrator.workerPool().available(), "worker 必须全部归还，否则后续 borrow 会超时");
            assertEquals(1, stub.reviewerCalls(), "复核只跑一次，成本不随任务数增长");
            assertNotNull(result.reviewVerdict());
            assertTrue(result.reviewVerdict().approved());
            assertTrue(result.render().contains("✅ 复核通过"), result.render());
            assertTrue(result.render().contains("两步都已达成"), result.render());
        }
    }

    // ------------------------------------------------------------------ 辅助

    private AgentOrchestrator newOrchestrator(OrchestrationConfig config) {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stub, tools, config);
        orchestrator.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        return orchestrator;
    }

    /** 串行配置：并发度 1 → {@code OrchestrationConfig#serial()} 为 true。 */
    private static OrchestrationConfig serialConfig(int previewChars, boolean reviewer) {
        return new OrchestrationConfig(1, 1, 30_000L, 60_000L, reviewer, previewChars);
    }

    /** {@code task_1 → task_2} 的两步链式计划（id 会被 {@code ExecutionPlan} 重写为 task_N）。 */
    private static String chainPlan() {
        return """
                {"summary":"两步计划","tasks":[
                  {"id":"1","description":"读取配置文件","type":"FILE_READ","dependencies":[]},
                  {"id":"2","description":"分析配置差异","type":"ANALYSIS","dependencies":["1"]}
                ]}""";
    }
}
