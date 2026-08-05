package com.agentcli.audit;

import com.agentcli.agent.concurrent.AgentTask;
import com.agentcli.agent.concurrent.CancellationToken;
import com.agentcli.agent.orchestration.AgentRole;
import com.agentcli.agent.orchestration.ReviewVerdict;
import com.agentcli.store.SqliteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T05 编排轨迹落盘验收：事件写入 {@code audit_log}（{@code kind="orchestration"}）后可回读复盘。
 *
 * <p>本类置于 {@code com.agentcli.audit} 包内，是为了复用 {@link AuditLog#insert} 的包级可见性
 * 语义边界——{@link OrchestrationTrace} 正是靠它共表落盘，测试与被测代码在同一可见性域里更贴近真实调用。
 */
class OrchestrationTraceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLAN_ID = "plan_20200";

    @Test
    void plannedWritesASingleOrchestrationRow() throws Exception {
        AuditLog auditLog = newAuditLog();
        new OrchestrationTrace(auditLog).planned(PLAN_ID, 3);

        List<AuditEntry> rows = auditLog.query(20, OrchestrationTrace.KIND);
        assertEquals(1, rows.size());
        assertEquals(OrchestrationTrace.KIND, rows.get(0).kind());
        assertEquals(PLAN_ID, rows.get(0).target(), "规划事件的 target 就是计划 id");

        JsonNode detail = parse(rows.get(0));
        assertEquals(OrchestrationTrace.EVENT_PLANNED, detail.path("event").asText());
        assertEquals(3, detail.path("task_count").asInt());
    }

    @Test
    void taskEventsUseCompositeTargetAndCarryTerminalState() throws Exception {
        AuditLog auditLog = newAuditLog();
        OrchestrationTrace trace = new OrchestrationTrace(auditLog);
        AgentTask task = newTask("task_1", "读取配置文件");

        trace.dispatched(PLAN_ID, task);
        task.markRunning();
        task.markDone("端口 8080");
        trace.completed(PLAN_ID, task);

        List<AuditEntry> rows = auditLog.query(20, OrchestrationTrace.KIND);
        assertEquals(2, rows.size());
        for (AuditEntry row : rows) {
            assertEquals(PLAN_ID + ":task_1", row.target(),
                    "复合键既能按计划聚合，也能定位到具体子任务");
        }

        JsonNode dispatched = findEvent(rows, OrchestrationTrace.EVENT_DISPATCHED);
        assertEquals("task_1", dispatched.path("task_id").asText());
        assertEquals("读取配置文件", dispatched.path("task_name").asText());
        assertEquals(AgentRole.WORKER.name(), dispatched.path("role").asText());

        JsonNode completed = findEvent(rows, OrchestrationTrace.EVENT_COMPLETED);
        assertEquals("DONE", completed.path("state").asText());
    }

    @Test
    void failedAndCancelledAndTimedOutEventsAreDistinguishable() throws Exception {
        AuditLog auditLog = newAuditLog();
        OrchestrationTrace trace = new OrchestrationTrace(auditLog);

        AgentTask failed = newTask("task_1", "抓取数据");
        failed.markRunning();
        failed.markFailed("连接被重置".repeat(80));
        trace.failed(PLAN_ID, failed);

        AgentTask cancelled = newTask("task_2", "汇总材料");
        cancelled.markCancelled();
        trace.cancelled(PLAN_ID, cancelled);

        AgentTask timedOut = newTask("task_3", "巡检配置");
        timedOut.markRunning();
        timedOut.markTimeout();
        trace.timedOut(PLAN_ID, timedOut);

        List<AuditEntry> rows = auditLog.query(20, OrchestrationTrace.KIND);
        assertEquals(3, rows.size());

        JsonNode failedNode = findEvent(rows, OrchestrationTrace.EVENT_FAILED);
        assertEquals("FAILED", failedNode.path("state").asText());
        String error = failedNode.path("error").asText();
        assertTrue(error.startsWith("连接被重置"), error);
        assertTrue(error.length() <= 300, "错误摘要必须截断，不能把整段输出灌进审计表");
        assertTrue(error.endsWith("…"), error);

        assertEquals("CANCELLED", findEvent(rows, OrchestrationTrace.EVENT_CANCELLED).path("state").asText());
        assertEquals("TIMEOUT", findEvent(rows, OrchestrationTrace.EVENT_TIMED_OUT).path("state").asText());
    }

    @Test
    void reviewedRecordsApprovalAndIssues() throws Exception {
        AuditLog auditLog = newAuditLog();
        OrchestrationTrace trace = new OrchestrationTrace(auditLog);

        trace.reviewed(PLAN_ID, ReviewVerdict.rejected("缺少压测数据", "材料尚不完整"));
        trace.reviewed(PLAN_ID, ReviewVerdict.approved("已达成"));

        List<AuditEntry> rows = auditLog.query(20, OrchestrationTrace.KIND);
        assertEquals(2, rows.size());

        JsonNode rejected = rows.stream()
                .map(OrchestrationTraceTest::parseQuietly)
                .filter(node -> !node.path("approved").asBoolean(true))
                .findFirst()
                .orElseThrow();
        assertEquals(OrchestrationTrace.EVENT_REVIEWED, rejected.path("event").asText());
        assertEquals("缺少压测数据", rejected.path("issues").asText());

        JsonNode approved = rows.stream()
                .map(OrchestrationTraceTest::parseQuietly)
                .filter(node -> node.path("approved").asBoolean(false))
                .findFirst()
                .orElseThrow();
        assertFalse(approved.has("issues"), "通过时 issues 为空则不落该字段，省掉噪声");
    }

    @Test
    void nullAuditLogMakesEveryMethodNoOp() {
        OrchestrationTrace trace = new OrchestrationTrace(null);
        AgentTask task = newTask("task_1", "读取配置文件");

        // 未装配审计时是零成本 no-op：一次都不能抛
        trace.planned(PLAN_ID, 2);
        trace.dispatched(PLAN_ID, task);
        trace.completed(PLAN_ID, task);
        trace.failed(PLAN_ID, task);
        trace.cancelled(PLAN_ID, task);
        trace.timedOut(PLAN_ID, task);
        trace.reviewed(PLAN_ID, ReviewVerdict.approved("ok"));
        trace.reviewed(PLAN_ID, null);
    }

    @Test
    void nullTaskOrVerdictIsIgnoredWithoutWritingRows() throws Exception {
        AuditLog auditLog = newAuditLog();
        OrchestrationTrace trace = new OrchestrationTrace(auditLog);

        trace.dispatched(PLAN_ID, null);
        trace.completed(PLAN_ID, null);
        trace.reviewed(PLAN_ID, null);

        assertTrue(auditLog.query(20, OrchestrationTrace.KIND).isEmpty());
    }

    @Test
    void orchestrationRowsCoexistWithOtherAuditKinds() throws Exception {
        AuditLog auditLog = newAuditLog();
        auditLog.recordLlm("glm-4", 100, 20, 0, 500, null, false);
        new OrchestrationTrace(auditLog).planned(PLAN_ID, 1);

        assertEquals(2, auditLog.query(20).size(), "共用一张表，才能排出完整时间线");
        assertEquals(1, auditLog.query(20, OrchestrationTrace.KIND).size());
        assertEquals(1, auditLog.query(20, "llm").size());
    }

    // ------------------------------------------------------------------ 辅助

    private static AuditLog newAuditLog() throws Exception {
        Path dir = Files.createTempDirectory("agentcli-orchestration-trace-test");
        SqliteStore store = SqliteStore.open(dir.resolve("agentcli-test.db"));
        return new AuditLog(store);
    }

    private static AgentTask newTask(String id, String name) {
        return new AgentTask(id, name, AgentRole.WORKER, new CancellationToken());
    }

    private static JsonNode parse(AuditEntry entry) throws Exception {
        return MAPPER.readTree(entry.detailJson());
    }

    private static JsonNode parseQuietly(AuditEntry entry) {
        try {
            return MAPPER.readTree(entry.detailJson());
        } catch (Exception e) {
            throw new IllegalStateException("审计明细应为合法 JSON: " + entry.detailJson(), e);
        }
    }

    private static JsonNode findEvent(List<AuditEntry> rows, String event) {
        JsonNode found = rows.stream()
                .map(OrchestrationTraceTest::parseQuietly)
                .filter(node -> event.equals(node.path("event").asText()))
                .findFirst()
                .orElse(null);
        assertNotNull(found, "未找到事件: " + event);
        return found;
    }
}
