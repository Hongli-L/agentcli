package com.agentcli.agent.orchestration;

import com.agentcli.agent.concurrent.AgentTaskView;

import java.util.List;

/**
 * 一轮编排的最终产物。
 *
 * <p><b>为什么用 {@code degraded} 而不是 {@code success}</b>：多 Agent 编排的常态不是
 * 「全成功」或「全失败」，而是「3 个子任务成功 2 个、失败 1 个」。二值的 success
 * 会逼调用方在「谎报成功」和「丢弃已有产出」之间二选一。{@code degraded=true}
 * 表达的是「有产出，但不完整」——这正是部分失败降级要传达的语义。
 *
 * @param degraded      是否降级：存在 FAILED / TIMEOUT / CANCELLED / SKIPPED 的任务即为 true
 * @param tasks         各子任务的只读快照（可变实体不外泄给 CLI 层）
 * @param summaryText   聚合报告正文，同时也是喂给 reviewer 的输入
 * @param reviewVerdict 复核结论；reviewer 关闭或无任务可复核时为 {@code null}
 */
public record OrchestrationResult(boolean degraded,
                                  List<AgentTaskView> tasks,
                                  String summaryText,
                                  ReviewVerdict reviewVerdict) {

    public OrchestrationResult {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        summaryText = summaryText == null ? "" : summaryText;
    }

    /** 编排在进入执行阶段之前就失败（规划失败 / 循环依赖 / 目标为空）。 */
    public static OrchestrationResult failed(String message) {
        return new OrchestrationResult(true, List.of(), message == null ? "编排失败" : message, null);
    }

    /** 成功完成的任务数。 */
    public int doneCount() {
        return (int) tasks.stream().filter(AgentTaskView::isDone).count();
    }

    /** 未成功完成的任务数（失败 / 超时 / 取消）。 */
    public int failedCount() {
        return tasks.size() - doneCount();
    }

    /** 终端友好的完整渲染：聚合正文 + 复核结论。 */
    public String render() {
        StringBuilder sb = new StringBuilder(summaryText);
        if (reviewVerdict != null) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append('\n').append(reviewVerdict.render());
        }
        return sb.toString();
    }
}
