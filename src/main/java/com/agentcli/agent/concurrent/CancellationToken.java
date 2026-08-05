package com.agentcli.agent.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式取消令牌：三层传播中的 L1（协作标志）与 L3（句柄级取消）载体。
 *
 * <p>三层传播的分工：
 * <ol>
 *   <li><b>L1 协作轮询</b>：{@link #isCancelled()}，检查点在 ReAct 循环头、工具批次前、
 *       编排每批之间、SSE 每个 chunk。纯计算段能及时退出，无副作用。</li>
 *   <li><b>L2 线程中断</b>：{@code Future.cancel(true)}，唤醒 sleep / wait / BlockingQueue.take
 *       等可中断阻塞点。<b>唤醒不了阻塞在 socket read 上的 OkHttp/Okio</b>。</li>
 *   <li><b>L3 句柄级取消</b>：{@link #onCancel(Runnable)} 钩子表，注册
 *       {@code okhttp3.Call::cancel} 之类的关闭动作，兜住 L2 管不到的原生阻塞。</li>
 * </ol>
 *
 * <p><b>钩子生命周期约定</b>：{@link #onCancel(Runnable)} 返回句柄，调用方必须在 {@code finally}
 * 中 {@link #removeHook(Runnable)}。一次编排可能触发数十次 LLM 调用，不注销会让
 * {@link CopyOnWriteArrayList} 无限增长，而 CopyOnWrite 的写成本随长度线性上升。
 *
 * <p>本类所有方法线程安全。
 */
public final class CancellationToken {

    private static final Logger log = LoggerFactory.getLogger(CancellationToken.class);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Runnable> hooks = new CopyOnWriteArrayList<>();

    /**
     * 请求取消。幂等：仅第一次调用会触发钩子。
     *
     * <p>钩子抛出的异常只记日志、不上抛——取消是尽力而为的收尾动作，
     * 单个钩子失败不应阻断其余钩子与主流程。
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.warn("取消钩子执行失败，已忽略: {}", e.getMessage());
            }
        }
        // 钩子只需触发一次，触发后立刻释放引用，避免长会话累积
        hooks.clear();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 注册一个取消钩子（L3）。
     *
     * <p>若令牌已经取消，钩子会被立即执行并且不入表。返回值即为注销句柄。
     *
     * @return 注销句柄，调用方须在 {@code finally} 中传给 {@link #removeHook(Runnable)}
     */
    public Runnable onCancel(Runnable hook) {
        if (hook == null) {
            return null;
        }
        if (cancelled.get()) {
            runQuietly(hook);
            return hook;
        }
        hooks.add(hook);
        // 补偿 add 与 cancel 交错的竞态：cancel 可能刚好在 add 之前完成钩子遍历
        if (cancelled.get() && hooks.remove(hook)) {
            runQuietly(hook);
        }
        return hook;
    }

    /** 注销钩子。必须在 {@code finally} 中调用，防止钩子表随会话无限增长。 */
    public void removeHook(Runnable handle) {
        if (handle != null) {
            hooks.remove(handle);
        }
    }

    /** 当前登记的钩子数量，供测试断言「一次编排后钩子数归零」。 */
    public int hookCount() {
        return hooks.size();
    }

    /** 已取消时抛出 {@link CancelledException}，供不便返回值的调用点使用。 */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancelledException("任务已取消");
        }
    }

    private void runQuietly(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            log.warn("取消钩子执行失败，已忽略: {}", e.getMessage());
        }
    }

    /** 协作式取消的信号异常。继承 {@link RuntimeException} 以免污染既有方法签名。 */
    public static final class CancelledException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CancelledException(String message) {
            super(message);
        }
    }
}
