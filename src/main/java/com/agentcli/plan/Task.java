package com.agentcli.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划中的一个任务节点：可独立执行，可能依赖其它任务。
 *
 * <p>参考 {@code _reference/com/paicli/plan/Task.java} 的模型，按主项目约定收敛：
 * 状态/类型用枚举表达，依赖关系在 {@link ExecutionPlan#addTask} 时双向建立。
 */
public class Task {

    public enum TaskType {
        PLANNING,
        FILE_READ,
        FILE_WRITE,
        COMMAND,
        ANALYSIS,
        VERIFICATION
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    private final String id;
    private final String description;
    private final TaskType type;
    private volatile TaskStatus status;
    private volatile String result;
    private volatile String error;
    private final List<String> dependencies;
    private final List<String> dependents;
    private volatile long startTime;
    private volatile long endTime;

    public Task(String id, String description, TaskType type) {
        this(id, description, type, null);
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
        if (dependencies != null) {
            this.dependencies.addAll(dependencies);
        }
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public TaskType getType() {
        return type;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public List<String> getDependencies() {
        return new ArrayList<>(dependencies);
    }

    public List<String> getDependents() {
        return new ArrayList<>(dependents);
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /** 从持久化状态恢复（{@code PlanStore} 重建与 REPL 续跑均可用）。 */
    public void restoreState(TaskStatus status, String result, String error) {
        this.status = status;
        this.result = result;
        this.error = error;
    }

    /** 任务执行耗时（毫秒）。 */
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
     * 是否可立即执行：自身处于 PENDING，且所有依赖任务都已 COMPLETED。
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) {
            return false;
        }
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}
