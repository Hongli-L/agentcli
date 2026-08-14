package com.agentcli.common;

/**
 * 单轮工具调用配额：限制一次 LLM 响应能触发的工具调用数量，防止模型在一个回合里
 * 扇出过量调用（刷屏 / 资源耗尽 /  runaway）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>纯计数、无锁、单线程使用（每个 LLM 响应在 {@code ReActAgent} 主循环里同步消费一次），不引入并发开销</li>
 *   <li>配额上限来自 {@link SandboxPolicy}，本类只负责"能否再消费一次"的判定与剩余量查询</li>
 *   <li>用尽后 {@link #tryConsume()} 返回 false，由调用方决定是截断还是拒绝，本类不抛异常</li>
 * </ul>
 */
public final class ToolCallQuota {

    private final int max;
    private int used;

    public ToolCallQuota(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        this.max = max;
        this.used = 0;
    }

    /** 是否还能再消费一次。 */
    public boolean hasRemaining() {
        return used < max;
    }

    /** 尝试消费一次配额；成功返回 true，用尽返回 false。 */
    public boolean tryConsume() {
        if (used < max) {
            used++;
            return true;
        }
        return false;
    }

    /** 剩余可用次数。 */
    public int remaining() {
        return Math.max(0, max - used);
    }

    /** 已消费次数。 */
    public int used() {
        return used;
    }

    /** 配额上限。 */
    public int max() {
        return max;
    }
}
