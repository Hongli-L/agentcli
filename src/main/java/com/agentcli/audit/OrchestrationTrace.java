package com.agentcli.audit;

import com.agentcli.agent.concurrent.AgentTask;
import com.agentcli.agent.orchestration.ReviewVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 多 Agent 编排轨迹落盘器：把「规划 → 派发 → 终态 → 复核」写进审计表，事后可复盘。
 *
 * <p><b>为什么复用 {@code audit_log} 而不新建一张表</b>：编排事件与 LLM 调用、工具审批
 * 在观测语义上同源（都是「这次会话干了什么」的时间线），共表才能按 {@code ts} 排出一条
 * 完整时间线；新建表会强迫查询方做跨表 UNION。区分靠 {@code kind="orchestration"}，
 * 现有 {@link AuditLog#query(int, String)} 的按类型过滤天然可用。
 *
 * <p><b>落盘是旁路，绝不影响编排</b>：{@link AuditLog#insert} 内部已吞掉 SQL 异常，
 * 本类再兜一层 {@code RuntimeException}（如 JSON 序列化失败），最坏情况只往
 * {@code System.err} 打一行告警。编排结果不因为「日志写不进去」而改变。
 *
 * <p><b>trace 允许为 null</b>：{@code AgentOrchestrator} 里所有埋点都判空后调用，
 * 未装配审计（如单元测试、无持久化模式）时是零成本 no-op。
 *
 * <p><b>为什么本类放在 {@code audit} 包而不是 {@code orchestration} 包</b>：它要调用
 * {@link AuditLog#insert} —— 那是包级可见的写入口。放这里可以直接复用，放到编排包则必须把
 * {@code insert} 提升为 {@code public}，等于为一个内部写路径开一扇公共门，
 * 后续任何调用方都能绕过 {@code llmCall} / {@code approval} 这些语义化方法乱写审计表。
 * 代价是 {@code audit} 与 {@code agent.orchestration} 在<b>包</b>层面互相引用；但依赖的
 * 两个类型（{@link AgentTask}、{@link ReviewVerdict}）都是纯数据载体、不反向依赖 {@code audit}，
 * 所以<b>类</b>层面的依赖图仍是无环的。用「包级环」换「封装不被撕开」，这笔交易划算。
 *
 * <p>线程安全：无可变字段；{@link AuditLog} 每次操作开短连接，可并发调用。
 * 但实际调用点都在编排线程上（派发/回写都发生在编排线程），不构成热点。
 */
public final class OrchestrationTrace {

    /** 审计表中的事件类型标识。 */
    public static final String KIND = "orchestration";

    /** 事件名：规划完成。 */
    public static final String EVENT_PLANNED = "planned";

    /** 事件名：子任务派发。 */
    public static final String EVENT_DISPATCHED = "dispatched";

    /** 事件名：子任务成功。 */
    public static final String EVENT_COMPLETED = "completed";

    /** 事件名：子任务失败。 */
    public static final String EVENT_FAILED = "failed";

    /** 事件名：子任务被取消。 */
    public static final String EVENT_CANCELLED = "cancelled";

    /** 事件名：子任务超时。 */
    public static final String EVENT_TIMED_OUT = "timed_out";

    /** 事件名：聚合结果复核完成。 */
    public static final String EVENT_REVIEWED = "reviewed";

    /** 落盘的错误/问题摘要最大长度，避免把整段 LLM 输出塞进审计表。 */
    private static final int DETAIL_PREVIEW_CHARS = 300;

    private final AuditLog auditLog;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param auditLog 审计日志；为 {@code null} 时本对象所有方法都是 no-op
     */
    public OrchestrationTrace(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** 规划完成：记录计划 id 与子任务总数。 */
    public void planned(String planId, int taskCount) {
        if (auditLog == null) {
            return;
        }
        try {
            ObjectNode detail = baseNode(EVENT_PLANNED, planId);
            detail.put("task_count", taskCount);
            auditLog.insert(KIND, planId, detail.toString(), null, null);
        } catch (RuntimeException e) {
            warn(EVENT_PLANNED, e);
        }
    }

    /** 子任务派发：此时任务尚未开始跑，耗时字段无意义故不写。 */
    public void dispatched(String planId, AgentTask task) {
        emit(EVENT_DISPATCHED, planId, task, false);
    }

    /** 子任务成功。 */
    public void completed(String planId, AgentTask task) {
        emit(EVENT_COMPLETED, planId, task, true);
    }

    /** 子任务失败。 */
    public void failed(String planId, AgentTask task) {
        emit(EVENT_FAILED, planId, task, true);
    }

    /** 子任务被取消。 */
    public void cancelled(String planId, AgentTask task) {
        emit(EVENT_CANCELLED, planId, task, true);
    }

    /** 子任务超时。 */
    public void timedOut(String planId, AgentTask task) {
        emit(EVENT_TIMED_OUT, planId, task, true);
    }

    /** 聚合结果复核完成：记录是否通过与问题摘要。 */
    public void reviewed(String planId, ReviewVerdict verdict) {
        if (auditLog == null || verdict == null) {
            return;
        }
        try {
            ObjectNode detail = baseNode(EVENT_REVIEWED, planId);
            detail.put("approved", verdict.approved());
            String issues = preview(verdict.issues());
            if (!issues.isEmpty()) {
                detail.put("issues", issues);
            }
            auditLog.insert(KIND, planId, detail.toString(), null, null);
        } catch (RuntimeException e) {
            warn(EVENT_REVIEWED, e);
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 统一的任务级事件写入。
     *
     * @param withDuration 是否落 {@code latency_ms}（派发事件为 false：任务还没跑，耗时恒为 0 无意义）
     */
    private void emit(String event, String planId, AgentTask task, boolean withDuration) {
        if (auditLog == null || task == null) {
            return;
        }
        try {
            ObjectNode detail = baseNode(event, planId);
            detail.put("task_id", task.id());
            detail.put("task_name", preview(task.name()));
            detail.put("role", task.role().name());
            detail.put("state", task.state().name());
            String error = preview(task.error());
            if (!error.isEmpty()) {
                detail.put("error", error);
            }
            Long latency = withDuration ? task.durationMs() : null;
            // target 用 planId:taskId 复合键：既能按计划聚合，也能定位到具体子任务
            auditLog.insert(KIND, planId + ":" + task.id(), detail.toString(), null, latency);
        } catch (RuntimeException e) {
            warn(event, e);
        }
    }

    private ObjectNode baseNode(String event, String planId) {
        ObjectNode detail = json.createObjectNode();
        detail.put("event", event);
        detail.put("plan_id", planId == null ? "" : planId);
        return detail;
    }

    private static String preview(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String single = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim();
        return single.length() <= DETAIL_PREVIEW_CHARS
                ? single
                : single.substring(0, DETAIL_PREVIEW_CHARS - 1) + "…";
    }

    private static void warn(String event, RuntimeException e) {
        System.err.println("[orchestration-trace] 记录失败 (" + event + "): " + e.getMessage());
    }
}
