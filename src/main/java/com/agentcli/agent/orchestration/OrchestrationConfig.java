package com.agentcli.agent.orchestration;

/**
 * 编排参数。全部为不可变值，构造后可安全跨线程共享。
 *
 * <p><b>默认值取舍</b>（{@link #defaults()}）：
 * <ul>
 *   <li>{@code maxParallel = 3}：并发放大会等比放大 token 消耗，3 是「有并行收益但账单可控」的折中；
 *       取值 {@code <= 1} 时 {@code AgentOrchestrator} 自动走串行策略。</li>
 *   <li>{@code workerCount = 3}：与 {@code maxParallel} 对齐，池中 worker 不会成为并发瓶颈，
 *       也不会预创建出永远借不出去的冗余实例。</li>
 *   <li>{@code perTaskTimeoutMs = 120000}：单个子任务 2 分钟，覆盖「多轮 ReAct + 若干次工具调用」的正常耗时。</li>
 *   <li>{@code globalTimeoutMs = 600000}：整场编排 10 分钟熔断，避免长尾任务把会话拖死。</li>
 *   <li>{@code reviewerEnabled = true}：只对聚合结果复核一次，成本约为一个 worker 的量级。</li>
 *   <li>{@code depResultPreviewChars = 500}：传给下游的依赖结果摘要长度，只传摘要不传对话历史。</li>
 * </ul>
 *
 * <p>本版<b>不提供 {@code /config} 运行时调参入口</b>：编排参数与 token 成本强相关，
 * 交由启动装配处统一决定，避免会话中途改并发度导致池尺寸与执行器尺寸不一致。
 */
public record OrchestrationConfig(
        int maxParallel,
        int workerCount,
        long perTaskTimeoutMs,
        long globalTimeoutMs,
        boolean reviewerEnabled,
        int depResultPreviewChars
) {

    /** 默认并发度。 */
    public static final int DEFAULT_MAX_PARALLEL = 3;

    /** 默认 worker 池尺寸。 */
    public static final int DEFAULT_WORKER_COUNT = 3;

    /** 默认单任务超时（毫秒）。 */
    public static final long DEFAULT_PER_TASK_TIMEOUT_MS = 120_000L;

    /** 默认全局超时（毫秒）。 */
    public static final long DEFAULT_GLOBAL_TIMEOUT_MS = 600_000L;

    /** 默认依赖结果摘要长度（字符）。 */
    public static final int DEFAULT_DEP_RESULT_PREVIEW_CHARS = 500;

    public OrchestrationConfig {
        if (maxParallel < 1) {
            throw new IllegalArgumentException("maxParallel 必须 >= 1，当前: " + maxParallel);
        }
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount 必须 >= 1，当前: " + workerCount);
        }
        if (perTaskTimeoutMs < 1) {
            throw new IllegalArgumentException("perTaskTimeoutMs 必须 > 0，当前: " + perTaskTimeoutMs);
        }
        if (globalTimeoutMs < 1) {
            throw new IllegalArgumentException("globalTimeoutMs 必须 > 0，当前: " + globalTimeoutMs);
        }
        if (depResultPreviewChars < 1) {
            throw new IllegalArgumentException("depResultPreviewChars 必须 > 0，当前: " + depResultPreviewChars);
        }
    }

    /** 生产默认配置。 */
    public static OrchestrationConfig defaults() {
        return new OrchestrationConfig(
                DEFAULT_MAX_PARALLEL,
                DEFAULT_WORKER_COUNT,
                DEFAULT_PER_TASK_TIMEOUT_MS,
                DEFAULT_GLOBAL_TIMEOUT_MS,
                true,
                DEFAULT_DEP_RESULT_PREVIEW_CHARS);
    }

    /** 是否应走串行策略（并发度为 1 时并行分批没有收益，反而多一层线程切换）。 */
    public boolean serial() {
        return maxParallel <= 1;
    }
}
