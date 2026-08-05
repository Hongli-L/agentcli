package com.agentcli.agent.orchestration;

import com.agentcli.agent.concurrent.AgentTaskExecutor;
import com.agentcli.testsupport.ConcurrentStubLlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04 复核 Agent 验收：JSON 解析、关键词兜底、保守判定与异常收敛。
 *
 * <p>核心口径只有一句：<b>看不懂的复核输出等价于没复核过，一律判不通过</b>。
 * 若默认放行，reviewer 会在模型抽风时静默退化成「永远通过」，比不做复核更危险。
 */
class ReviewerAgentTest {

    private static final String GOAL = "梳理支付链路风险并输出评审材料";
    private static final String AGGREGATE = "✅ task_1 抓取数据\n✅ task_2 汇总材料\n完成 2/2";

    private ConcurrentStubLlmClient stub;
    private ToolRegistry tools;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        stub = new ConcurrentStubLlmClient("stub", "stub");
        tools = new ToolRegistry(projectRoot.toString());
    }

    @Test
    void parsesStrictJsonVerdict() {
        stub.withReview("{\"approved\": true, \"issues\": \"\", \"summary\": \"两项产出均已达成\"}");

        ReviewVerdict verdict = new ReviewerAgent(stub, tools).review(GOAL, AGGREGATE);

        assertTrue(verdict.approved());
        assertEquals("", verdict.issues());
        assertEquals("两项产出均已达成", verdict.summary());
        assertEquals(1, stub.reviewerCalls());
        assertTrue(verdict.render().startsWith("✅ 复核通过"), verdict.render());
    }

    @Test
    void stripsMarkdownFenceAndExtractsIssues() {
        stub.withReview("""
                好的，以下是复核结果：
                ```json
                {"approved": false, "issues": "缺少压测数据支撑", "summary": "材料尚不完整"}
                ```
                """);

        ReviewVerdict verdict = new ReviewerAgent(stub, tools).review(GOAL, AGGREGATE);

        assertFalse(verdict.approved());
        assertEquals("缺少压测数据支撑", verdict.issues());
        assertEquals("材料尚不完整", verdict.summary());
        assertTrue(verdict.render().contains("⚠️ 复核未通过"), verdict.render());
        assertTrue(verdict.render().contains("问题：缺少压测数据支撑"), verdict.render());
    }

    @Test
    void fallsBackToKeywordsWhenOutputIsNotJson() {
        stub.withReview("经复核，三个子任务的产出合起来已达成总目标。");

        ReviewVerdict verdict = new ReviewerAgent(stub, tools).review(GOAL, AGGREGATE);

        assertTrue(verdict.approved(), "非 JSON 输出应能靠关键词兜底判定");
        assertTrue(verdict.issues().contains("已达成总目标"), "兜底时 issues 回退为原文摘要");
    }

    @Test
    void negationIsCheckedBeforeAffirmationSoBuTongGuoIsNotReadAsTongGuo() {
        ReviewerAgent reviewer = new ReviewerAgent(stub, tools);

        // 「不通过」内含「通过」——顺序反了就会把否定读成肯定，这是关键词兜底最容易踩的坑
        assertFalse(reviewer.parseApproval("复核结论：不通过，缺少验证步骤"));
        assertFalse(reviewer.parseApproval("目标未达成"));
        assertFalse(reviewer.parseApproval("not approved"));
        assertTrue(reviewer.parseApproval("复核通过"));
        assertTrue(reviewer.parseApproval("{\"approved\":true}"));
        assertFalse(reviewer.parseApproval(null), "空输入保守判不通过");
        assertFalse(reviewer.parseApproval("   "));
    }

    @Test
    void blankOutputIsConservativelyRejected() {
        stub.withReview("");

        ReviewVerdict verdict = new ReviewerAgent(stub, tools).review(GOAL, AGGREGATE);

        assertFalse(verdict.approved());
        assertEquals(ReviewVerdict.REVIEW_UNAVAILABLE, verdict.summary());
        assertTrue(verdict.issues().contains("复核输出为空"), verdict.issues());
    }

    @Test
    void reviewFailureIsConvergedIntoUnavailableVerdictInsteadOfThrowing() {
        stub.withReviewFailure(new IllegalStateException("复核服务 500"));

        ReviewVerdict verdict = new ReviewerAgent(stub, tools).review(GOAL, AGGREGATE);

        assertFalse(verdict.approved(), "复核链路自身出错也判不通过");
        assertTrue(verdict.issues().contains(ReviewVerdict.REVIEW_UNAVAILABLE), verdict.issues());
        assertTrue(verdict.issues().contains("复核服务 500"), verdict.issues());
    }

    @Test
    void cancelledReviewIsReportedAsUnavailableRatherThanRejection() {
        stub.withReview(AgentTaskExecutor.CANCELLED_RESULT);

        ReviewVerdict verdict = new ReviewerAgent(stub, tools).review(GOAL, AGGREGATE);

        assertFalse(verdict.approved());
        assertTrue(verdict.issues().contains("复核已被取消"), verdict.issues());
    }
}
