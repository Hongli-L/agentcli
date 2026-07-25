package com.agentcli.plan;

import com.agentcli.agent.AgentRunListener;
import com.agentcli.agent.ReActAgent;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute 编排器：先规划（{@link Planner}），再按依赖顺序逐步执行。
 *
 * <p>每个子任务交给一个独立的 {@link ReActAgent} 运行（共享同一 {@code LlmClient} 与
 * {@code ToolRegistry}），从而天然复用既有的并行工具调度、HITL 审批与 MCP 工具桥接，
 * 且各任务对话历史相互隔离。
 *
 * <p>参考 {@code _reference/com/paicli/agent/PlanExecuteAgent.java} 的职责划分，按主项目
 * 约定收敛：不在本类内重写 ReAct 循环，而是把单任务执行委托给既有的 {@link ReActAgent}。
 */
public class PlanExecuteAgent {

    /** 单任务执行器：输入任务上下文文本，返回任务结果。 */
    @FunctionalInterface
    public interface TaskExecutor {
        String execute(String taskPrompt) throws IOException;
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private AgentRunListener listener = AgentRunListener.NO_OP;
    private PrintStream out = System.out;
    private TaskExecutor taskExecutor;
    private PlanStore planStore;

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new Planner(llmClient));
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.taskExecutor = (taskPrompt) -> {
            ReActAgent taskAgent = new ReActAgent(llmClient, toolRegistry);
            taskAgent.setListener(PlanExecuteAgent.this.listener);
            return taskAgent.run(taskPrompt);
        };
    }

    public void setListener(AgentRunListener listener) {
        this.listener = listener == null ? AgentRunListener.NO_OP : listener;
    }

    public void setOut(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    /** 注入自定义任务执行器（主要用于测试，避免真实 LLM 调用）。 */
    public void setTaskExecutor(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /** 注入 Plan 持久化存储（可选）；设置后计划与任务状态变更会落盘，支持断点续跑。 */
    public void setPlanStore(PlanStore planStore) {
        this.planStore = planStore;
    }

    /** 顶层入口：规划 + 执行，返回最终摘要文本。 */
    public String run(String goal) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return executePlan(plan);
    }

    /** 执行已构建好的计划（可被外部复用，例如测试或 REPL 命令）。 */
    public String executePlan(ExecutionPlan plan) throws IOException {
        plan.markStarted();
        persistPlan(plan);
        out.println("Executing plan: " + plan.getGoal());
        StringBuilder finalResult = new StringBuilder();
        int replanAttempts = 0;

        while (true) {
            List<Task> batch = getExecutableTasksInOrder(plan);
            if (batch.isEmpty()) {
                break;
            }
            for (Task task : batch) {
                task.markStarted();
                persistTask(plan, task);
                out.println("> Executing [" + task.getId() + "]: " + task.getDescription());
                String result;
                try {
                    result = taskExecutor.execute(buildTaskContext(plan.getGoal(), plan, task));
                } catch (Exception e) {
                    task.markFailed(e.getMessage());
                    persistTask(plan, task);
                    out.println("  x failed [" + task.getId() + "]: " + e.getMessage());
                    if (plan.getProgress() < 0.5 && replanAttempts < 1) {
                        replanAttempts++;
                        out.println("  Replanning...");
                        try {
                            plan = planner.replan(plan, e.getMessage());
                            persistPlan(plan);
                        } catch (IOException re) {
                            out.println("  Replan failed: " + re.getMessage());
                        }
                        break; // 用新计划重启外层循环
                    }
                    if (!finalResult.isEmpty()) {
                        finalResult.append("\n");
                    }
                    finalResult.append("Task ").append(task.getId()).append(" failed: ").append(e.getMessage());
                    continue;
                }
                task.markCompleted(result);
                persistTask(plan, task);
                out.println("  ok [" + task.getId() + "]");
            }
        }

        if (plan.hasFailed() || !plan.isAllCompleted()) {
            plan.markFailed();
            persistPlan(plan);
            return finalResult.isEmpty()
                    ? "Plan stalled: some tasks failed or dependencies unmet."
                    : "Plan partially completed; some tasks failed.\n" + finalResult;
        }
        plan.markCompleted();
        persistPlan(plan);
        return "Plan executed successfully.";
    }

    private void persistPlan(ExecutionPlan plan) {
        if (planStore != null) {
            planStore.savePlan(plan);
        }
    }

    private void persistTask(ExecutionPlan plan, Task task) {
        if (planStore != null) {
            planStore.saveTask(plan.getId(), task);
        }
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append('\n');
        context.append("当前任务：").append(task.getDescription()).append('\n');
        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus()).append('\n');
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append('\n');
                }
            }
        }
        context.append("请执行此任务。如果是 ANALYSIS 或 VERIFICATION 类型，请基于以上上下文直接给出结论。");
        return context.toString();
    }
}
