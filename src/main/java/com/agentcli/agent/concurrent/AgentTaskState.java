package com.agentcli.agent.concurrent;

/**
 * 并发任务的生命周期状态（六态）。
 *
 * <p>与 {@code plan.Task.TaskStatus} 的区别是语义层次不同：{@code plan.Task} 表达「计划语义」
 * （DAG 节点、落 {@code plan_tasks} 表、参与 {@code /plan resume} 断点续跑）；本枚举表达
 * 「执行语义」（并发生命周期、超时、取消）。二者 1:1 关联但刻意不合并，避免把 {@code TIMEOUT}
 * / {@code CANCELLED} 塞进已持久化的表 schema。
 *
 * <p>迁移表由 {@link #canTransitionTo(AgentTaskState)} 表达，四个终态一旦落定即不可再迁移，
 * 由 {@link AgentTask} 用 CAS 保证并发下的 first-writer-wins。
 *
 * <pre>
 *                  ┌──────────► CANCELLED   （取消发生在入队后、执行前）
 *                  │
 *  PENDING ──────► RUNNING ──┬──► DONE
 *                            ├──► FAILED
 *                            ├──► CANCELLED
 *                            └──► TIMEOUT
 * </pre>
 */
public enum AgentTaskState {

    /** 已创建 / 已入队，尚未开始执行。 */
    PENDING,

    /** 已被池线程领取，正在执行。 */
    RUNNING,

    /** 正常完成，携带结果文本。 */
    DONE,

    /** 执行过程中抛出异常，携带错误信息。 */
    FAILED,

    /** 被协作式取消（{@code /cancel} 或全局熔断）。 */
    CANCELLED,

    /** 触发单任务超时预算。 */
    TIMEOUT;

    /** 是否为终态：终态不可再迁移，重复 mark 为幂等 no-op。 */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }

    /**
     * 是否允许迁移到目标状态。
     *
     * <p>非法迁移返回 {@code false} 而不抛异常：超时线程与 worker 线程天然会并发竞争终态，
     * 抛异常会把正常竞态变成噪声日志，并污染降级路径。
     */
    public boolean canTransitionTo(AgentTaskState to) {
        if (to == null || to == this || isTerminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> to == RUNNING || to == CANCELLED;
            case RUNNING -> to == DONE || to == FAILED || to == CANCELLED || to == TIMEOUT;
            default -> false;
        };
    }
}
