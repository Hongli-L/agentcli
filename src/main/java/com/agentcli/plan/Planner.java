package com.agentcli.plan;

import com.agentcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * 规划器：用 LLM 把复杂任务分解为带依赖的 {@link ExecutionPlan}。
 *
 * <p>参考 {@code _reference/com/paicli/plan/Planner.java} 的职责划分，按主项目约定收敛：
 * 简单目标直接生成单任务最小计划（不调用 LLM），复杂目标才让 LLM 产出 JSON 计划。
 */
public class Planner {

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是一个任务规划器。给定一个复杂任务，请把它分解为若干可独立执行的子任务，并输出严格的 JSON（不要包含任何解释或 markdown 代码块围栏）。

            输出格式：
            {
              "summary": "一句话概括整个任务",
              "tasks": [
                {
                  "id": "1",
                  "description": "这个子任务要做什么",
                  "type": "FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
                  "dependencies": []
                }
              ]
            }

            规则：
            - 每个 task 必须有唯一 id（字符串），dependencies 引用其它 task 的 id。
            - 只有真正需要按顺序完成的任务才加依赖；可并行的任务不要互相依赖。
            - type 按任务主要性质选择：读取文件用 FILE_READ，写入/修改文件用 FILE_WRITE，执行 shell 命令用 COMMAND，分析/总结用 ANALYSIS，验证/检查用 VERIFICATION。
            - 只输出 JSON。""";

    private final LlmClient llmClient;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    /** 为复杂任务创建执行计划（简单任务走最小计划）。 */
    public ExecutionPlan createPlan(String goal) throws IOException {
        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }
        out.println("Planning task: " + goal);
        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(PLANNER_SYSTEM_PROMPT),
                LlmClient.Message.user("请为以下任务制定执行计划：\n" + goal)
        );
        LlmClient.ChatResponse response = llmClient.chat(messages, null, LlmClient.StreamListener.NO_OP);
        return ExecutionPlan.fromJson(mapper, goal, response.content());
    }

    /** 基于已失败计划的上下文重新规划。 */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        out.println("Replanning, reason: " + failureReason);
        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append('\n');
        context.append("失败原因: ").append(failureReason).append('\n');
        context.append("已完成的任务:\n");
        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId()).append(": ").append(task.getDescription()).append('\n');
            }
        }
        context.append("\n请制定新的执行计划，避开之前的问题。");
        return createPlan(context.toString());
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }
        String normalized = goal.trim();
        if (normalized.isEmpty() || normalized.length() > 30) {
            return false;
        }
        boolean multiStep = normalized.contains("然后") || normalized.contains("并且")
                || normalized.contains("并") || normalized.contains("再") || normalized.contains("最后")
                || normalized.contains("同时") || normalized.contains("先") || normalized.contains("之后")
                || normalized.contains("接着") || normalized.contains("以及");
        return !multiStep && (normalized.contains("列出") || normalized.contains("查看")
                || normalized.contains("读取") || normalized.contains("显示") || normalized.contains("执行")
                || normalized.contains("运行") || normalized.contains("搜索") || normalized.contains("当前目录")
                || normalized.contains("文件"));
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary("直接执行简单任务：" + goal.trim());
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看") && normalized.contains("文件")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    private static String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }
}
