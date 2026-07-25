package com.agentcli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 执行计划：一组带依赖关系的任务，按拓扑顺序执行。
 *
 * <p>参考 {@code _reference/com/paicli/plan/ExecutionPlan.java} 的模型，按主项目约定收敛：
 * 用 {@link #fromJson} 把 LLM 产出的 JSON 计划解析为内存模型，并做循环依赖检测。
 */
public class ExecutionPlan {

    public enum PlanStatus {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String id;
    private final String goal;
    private final Map<String, Task> tasks;
    private final List<String> executionOrder;
    private PlanStatus status;
    private String summary;
    private long startTime;
    private long endTime;

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    public String getId() {
        return id;
    }

    public String getGoal() {
        return goal;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    /** 添加任务，并双向建立依赖 / 被依赖关系。 */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    public Task getTask(String id) {
        return tasks.get(id);
    }

    public List<Task> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /** 根任务（没有任何依赖）。 */
    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    /** 当前可执行的任务（依赖都已 COMPLETED）。 */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    /** 计算拓扑排序执行顺序，返回 false 表示存在环。 */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();
        if (visiting.contains(id)) {
            return false;
        }
        if (visited.contains(id)) {
            return true;
        }
        visiting.add(id);
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null && !topologicalSort(dep, visited, visiting)) {
                return false;
            }
        }
        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);
        return true;
    }

    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    /** 完成进度（0.0 ~ 1.0）。 */
    public double getProgress() {
        if (tasks.isEmpty()) {
            return 1.0;
        }
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED);
    }

    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    public void markCancelled() {
        this.status = PlanStatus.CANCELLED;
        this.endTime = System.currentTimeMillis();
    }

    /** 从持久化状态恢复计划级状态（仅供 {@code PlanStore} 重建使用）。 */
    void restoreStatus(PlanStatus status) {
        this.status = status;
    }

    public long getDuration() {
        if (startTime == 0) {
            return 0;
        }
        if (endTime == 0) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }

    /**
     * 把 LLM 产出的 JSON 计划解析为 {@link ExecutionPlan}。
     *
     * <p>期望结构： {@code {"summary":"...", "tasks":[{"id":"1","description":"...","type":"FILE_READ","dependencies":["1"]}]}}。
     * 任务 id 会被重写为 {@code task_N} 以避免 LLM 给出的 id 冲突或前向引用问题。
     *
     * @throws IOException 当 JSON 非法或存在循环依赖时
     */
    public static ExecutionPlan fromJson(ObjectMapper mapper, String goal, String planJson) throws IOException {
        String cleaned = planJson
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        JsonNode root = mapper.readTree(cleaned);
        if (!root.isObject()) {
            throw new IOException("计划 JSON 根节点应为对象");
        }
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray()) {
            throw new IOException("计划 JSON 缺少 tasks 数组");
        }

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        Map<String, String> idMapping = new LinkedHashMap<>();
        int index = 1;
        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + index++;
            idMapping.put(originalId, newId);
            String description = taskNode.path("description").asText();
            Task.TaskType type = parseType(taskNode.path("type").asText());
            plan.addTask(new Task(newId, description, type));
        }

        index = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + index++;
            Task task = plan.getTask(newId);
            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    Task dep = plan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }

        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }
        return plan;
    }

    private static Task.TaskType parseType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return Task.TaskType.ANALYSIS;
        }
        return switch (typeStr.trim().toUpperCase()) {
            case "PLANNING" -> Task.TaskType.PLANNING;
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    private static String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /** 终端友好的精简摘要（不展开完整 DAG）。 */
    public String summarize() {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan summary").append('\n');
        sb.append("  goal: ").append(compact(goal, 60)).append('\n');
        sb.append("  tasks: ").append(tasks.size())
                .append(" | executable: ").append(getExecutableTasks().size())
                .append(" | progress: ").append(String.format("%.0f%%", getProgress() * 100))
                .append(" | status: ").append(status).append('\n');
        return sb.toString();
    }

    /**
     * 终端友好的完整计划视图（含拓扑顺序、状态、依赖）。
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("+").append("-".repeat(60)).append("+\n");
        sb.append("| Plan: ").append(truncate(goal, 50)).append('\n');
        sb.append("+").append("-".repeat(60)).append("+\n");
        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            Task task = tasks.get(order.get(i));
            String deps = task.getDependencies().isEmpty() ? "none" : String.join(",", task.getDependencies());
            sb.append(String.format("| %d. [%s] %s%n", i + 1, task.getStatus(), task.getId()));
            sb.append("|    ").append(truncate(task.getDescription(), 52)).append('\n');
            sb.append("|    deps: ").append(deps).append('\n');
        }
        sb.append("+").append("-".repeat(60)).append("+\n");
        sb.append(String.format("  progress: %.0f%% | status: %s%n", getProgress() * 100, status));
        return sb.toString();
    }

    private static String compact(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String single = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim().replaceAll(" {2,}", " ");
        return single.length() <= max ? single : single.substring(0, max - 3) + "...";
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String single = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim();
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)", id, goal, tasks.size(), status);
    }
}
