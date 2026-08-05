package com.agentcli.agent.orchestration;

import com.agentcli.plan.ExecutionPlan;
import com.agentcli.plan.Task;

/**
 * 子任务 prompt 构造器：多 Agent 协作的<b>上下文隔离</b>落点。
 *
 * <p><b>隔离的是什么</b>：下游任务只能看到上游任务的<b>结果摘要</b>，看不到上游 worker 的
 * 对话历史（thought / tool_call / observation 全链路）。原因有三：
 * <ol>
 *   <li><b>成本</b>：一条 ReAct 链的历史动辄数千 token，逐级累加会让最后一个任务的 prompt 爆掉；</li>
 *   <li><b>噪声</b>：上游的试错过程（失败的工具调用、被推翻的假设）会误导下游；</li>
 *   <li><b>并发</b>：共享历史意味着共享可变列表，必然引入跨线程同步。</li>
 * </ol>
 *
 * <p>摘要长度由 {@code OrchestrationConfig.depResultPreviewChars} 控制（默认 500 字符），
 * 超长按字符截断并追加省略号——不做「再调一次 LLM 压缩」，那会把编排成本翻倍。
 *
 * <p>参考 {@code _reference/com/paicli/agent/AgentOrchestrator.java} 的上下文传递思路，
 * 按主项目约定收敛为无状态工具类，便于单测直接断言 prompt 文本。
 */
public final class StepContextBuilder {

    private static final String NO_DEPENDENCY = "（无前置依赖，可直接开始）";

    private StepContextBuilder() {
    }

    /**
     * 为一个子任务构造独立的执行 prompt。
     *
     * @param plan         所属计划（提供总目标与依赖任务的结果）
     * @param task         当前子任务
     * @param previewChars 单个依赖结果的最大回显字符数，{@code <= 0} 时按 500 处理
     * @return 可直接交给 {@link WorkerAgent#execute(String)} 的完整 prompt
     */
    public static String build(ExecutionPlan plan, Task task, int previewChars) {
        if (plan == null || task == null) {
            throw new IllegalArgumentException("plan / task 不能为 null");
        }
        int limit = previewChars > 0 ? previewChars : OrchestrationConfig.DEFAULT_DEP_RESULT_PREVIEW_CHARS;

        StringBuilder prompt = new StringBuilder(512);
        prompt.append("【总目标】\n").append(nullToEmpty(plan.getGoal()).trim()).append("\n\n");
        prompt.append("【当前子任务】").append(task.getId()).append('\n');
        prompt.append(nullToEmpty(task.getDescription()).trim()).append('\n');
        prompt.append("【任务类型】").append(task.getType()).append("\n\n");

        prompt.append("【前置任务的产出】\n");
        String dependencies = renderDependencies(plan, task, limit);
        prompt.append(dependencies.isEmpty() ? NO_DEPENDENCY : dependencies).append("\n\n");

        prompt.append("【要求】\n");
        prompt.append("1. 只完成上面这一个子任务，不要顺手做别的子任务；\n");
        prompt.append("2. 需要用工具就直接调用，不要只描述你打算怎么做；\n");
        prompt.append("3. 完成后直接给出这个子任务的最终产出，不要复述任务描述。");
        return prompt.toString();
    }

    /** 逐个依赖渲染「id + 描述 + 结果摘要」；依赖缺失或未完成时如实标注，让 worker 自己决定要不要兜底。 */
    private static String renderDependencies(ExecutionPlan plan, Task task, int limit) {
        StringBuilder sb = new StringBuilder();
        for (String depId : task.getDependencies()) {
            Task dep = plan.getTask(depId);
            if (dep == null) {
                sb.append("- ").append(depId).append("：（依赖任务不存在）\n");
                continue;
            }
            sb.append("- ").append(dep.getId()).append('（').append(compact(dep.getDescription(), 40)).append("）：\n");
            if (dep.getStatus() == Task.TaskStatus.COMPLETED) {
                sb.append(indent(truncate(dep.getResult(), limit))).append('\n');
            } else {
                sb.append("  （该前置任务状态为 ").append(dep.getStatus()).append("，无可用产出）\n");
            }
        }
        return sb.toString();
    }

    /** 按字符截断，保留可读的省略标记与截断提示（让模型知道信息是被裁过的，而不是本来就这么短）。 */
    private static String truncate(String raw, int limit) {
        String text = nullToEmpty(raw).trim();
        if (text.isEmpty()) {
            return "（无输出）";
        }
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…（结果过长，已截断至 " + limit + " 字符）";
    }

    /** 多行文本统一缩进两格，让依赖块在 prompt 里有清晰的视觉层级。 */
    private static String indent(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (String line : text.split("\n", -1)) {
            sb.append("  ").append(line).append('\n');
        }
        // 去掉最后一个多余换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 压成单行并限长，用于依赖任务的简短标题。 */
    private static String compact(String raw, int max) {
        String single = nullToEmpty(raw)
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll(" {2,}", " ");
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw;
    }
}
