package com.agentcli.agent.concurrent;

import com.agentcli.agent.orchestration.AgentRole;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发任务实体：承载一个子任务的生命周期状态、结果、错误与取消令牌。
 *
 * <p><b>为什么状态用 CAS 而不是 volatile 直接赋值</b>：超时线程写 {@code TIMEOUT} 与 worker 线程写
 * {@code DONE} 是天然并发的，直接赋值会互相覆盖，导致 {@code /tasks} 显示的终态取决于线程调度。
 * 这里用 {@link AtomicReference} + {@link #transition(AgentTaskState)} 做 first-writer-wins：
 * 第一个写入终态的线程胜出，后续 mark 是幂等 no-op 并返回 {@code false}。
 *
 * <p><b>为什么迁移失败返回 false 而不抛异常</b>：并发竞争终态是正常现象，抛异常会把正常竞态
 * 变成噪声日志，并污染「部分失败降级」路径的错误统计。
 *
 * <p>线程安全：状态走 CAS，结果/错误/时间戳为 {@code volatile}，可安全跨线程读取。
 */
public final class AgentTask {

    private final String id;
    private final String name;
    private final AgentRole role;
    private final AtomicReference<AgentTaskState> state = new AtomicReference<>(AgentTaskState.PENDING);
    private final CancellationToken token;
    private final long createdAt = System.currentTimeMillis();

    private volatile String result;
    private volatile String error;
    private volatile long startedAt;
    private volatile long finishedAt;

    /**
     * @param id    任务 id，通常复用 {@code plan.Task} 的 id（如 {@code task_1}），供 {@code /cancel} 精确命中
     * @param name  任务描述，用于 {@code /tasks} 展示
     * @param role  角色，默认场景为 {@link AgentRole#WORKER}
     * @param token 取消令牌，允许为 {@code null}（此时自动创建一个独立令牌）
     */
    public AgentTask(String id, String name, AgentRole role, CancellationToken token) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("任务 id 不能为空");
        }
        this.id = id;
        this.name = name == null ? id : name;
        this.role = role == null ? AgentRole.WORKER : role;
        this.token = token == null ? new CancellationToken() : token;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public AgentRole role() {
        return role;
    }

    public AgentTaskState state() {
        return state.get();
    }

    public CancellationToken token() {
        return token;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    public long createdAt() {
        return createdAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    /** PENDING → RUNNING。返回 false 表示任务已被取消或已进入终态。 */
    public boolean markRunning() {
        if (!transition(AgentTaskState.RUNNING)) {
            return false;
        }
        startedAt = System.currentTimeMillis();
        return true;
    }

    /** RUNNING → DONE，落地结果。 */
    public boolean markDone(String result) {
        if (!transition(AgentTaskState.DONE)) {
            return false;
        }
        this.result = result == null ? "" : result;
        this.finishedAt = System.currentTimeMillis();
        return true;
    }

    /** RUNNING → FAILED，落地错误信息。 */
    public boolean markFailed(String error) {
        if (!transition(AgentTaskState.FAILED)) {
            return false;
        }
        this.error = error == null ? "未知错误" : error;
        this.finishedAt = System.currentTimeMillis();
        return true;
    }

    /** PENDING/RUNNING → CANCELLED。取消可发生在入队后、执行前。 */
    public boolean markCancelled() {
        if (!transition(AgentTaskState.CANCELLED)) {
            return false;
        }
        this.error = "已取消";
        this.finishedAt = System.currentTimeMillis();
        return true;
    }

    /** RUNNING → TIMEOUT。仅在单任务超时预算耗尽时由回收路径调用。 */
    public boolean markTimeout() {
        if (!transition(AgentTaskState.TIMEOUT)) {
            return false;
        }
        this.error = "任务超时";
        this.finishedAt = System.currentTimeMillis();
        return true;
    }

    /** 执行耗时：未开始为 0，未结束按「至今」计算。 */
    public long durationMs() {
        long begin = startedAt;
        if (begin == 0L) {
            return 0L;
        }
        long end = finishedAt;
        return end == 0L ? System.currentTimeMillis() - begin : end - begin;
    }

    /** 生成只读快照。 */
    public AgentTaskView toView() {
        return new AgentTaskView(id, name, role, state.get(), durationMs(), AgentTaskView.previewOf(error));
    }

    /**
     * CAS 状态迁移。
     *
     * <p>循环重试直到 CAS 成功或确认迁移非法；终态一旦落定即拒绝任何后续迁移。
     */
    private boolean transition(AgentTaskState to) {
        while (true) {
            AgentTaskState current = state.get();
            if (current.isTerminal()) {
                return false;
            }
            if (!current.canTransitionTo(to)) {
                return false;
            }
            if (state.compareAndSet(current, to)) {
                return true;
            }
        }
    }

    @Override
    public String toString() {
        return "AgentTask[" + id + ", " + role.displayName() + ", " + state.get() + "]";
    }
}
