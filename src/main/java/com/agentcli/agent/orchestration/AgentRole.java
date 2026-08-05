package com.agentcli.agent.orchestration;

/**
 * 多 Agent 协作中的角色划分。
 *
 * <p>角色语义对齐 {@code _reference/com/paicli/agent/AgentOrchestrator.java} 的三段式协作模型：
 * planner 负责把目标拆成带依赖的 DAG，worker 负责执行单个子任务，reviewer 负责对聚合结果做质量复核。
 *
 * <p>{@link #displayName()} 用于 {@code /tasks} 表格渲染与审计轨迹的 {@code role} 字段，
 * 统一小写以便与 {@code audit_log.detail_json} 中的其它小写字段风格一致。
 */
public enum AgentRole {

    /** 规划者：把目标分解为带依赖关系的子任务集合。 */
    PLANNER("planner", "把目标分解为带依赖关系的子任务"),

    /** 执行者：领取单个子任务，独立上下文执行并产出结果。 */
    WORKER("worker", "执行单个子任务并产出结果"),

    /** 复核者：对聚合后的结果做一次质量复核，控制 token 成本。 */
    REVIEWER("reviewer", "对聚合结果做一次质量复核");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /** 终端与审计轨迹里展示的短名（小写）。 */
    public String displayName() {
        return displayName;
    }

    /** 角色职责的一句话说明。 */
    public String description() {
        return description;
    }
}
