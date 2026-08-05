package com.agentcli.agent.concurrent;

import java.util.concurrent.Callable;

/**
 * 取消令牌的线程作用域承载器：让 {@link CancellationToken} 在不改动
 * {@code LlmClient.chat(...)} 等既有接口签名的前提下抵达 HTTP 层。
 *
 * <p><b>为什么用普通 {@link ThreadLocal} 而不是 {@code InheritableThreadLocal}</b>：
 * 后者只在<b>线程创建时</b>做一次快照式继承。线程池会复用线程，池线程创建于第一批任务，
 * 之后再绑定的令牌根本传不进去——第二批及以后的任务会拿到<b>过期令牌</b>；同时线程存活期间
 * 令牌无法回收，构成泄漏。本类把绑定时机改为<b>任务开始执行时</b>，并在 {@code finally}
 * 中必定 {@code remove()}，池线程不会串味、不会泄漏。
 *
 * <p><b>零侵入契约</b>：{@link #current()} 返回 {@code null} 表示「不在可取消作用域内」，
 * 所有调用点必须按未引入取消能力之前的原行为执行。这是既有回归用例零改动的前提。
 */
public final class CancellationScope {

    private static final ThreadLocal<CancellationToken> CURRENT = new ThreadLocal<>();

    private CancellationScope() {
    }

    /** 当前线程绑定的取消令牌；未绑定时返回 {@code null}（调用方零侵入）。 */
    public static CancellationToken current() {
        return CURRENT.get();
    }

    /** 当前线程是否已被请求取消。{@code null} 令牌视为未取消。 */
    public static boolean isCancelled() {
        CancellationToken token = CURRENT.get();
        return token != null && token.isCancelled();
    }

    /** 在指定令牌作用域内执行有返回值的任务体，{@code finally} 必定解绑。 */
    public static <T> T call(CancellationToken token, Callable<T> body) throws Exception {
        CancellationToken previous = CURRENT.get();
        bind(token);
        try {
            return body.call();
        } finally {
            restore(previous);
        }
    }

    /** 在指定令牌作用域内执行无返回值的任务体，{@code finally} 必定解绑。 */
    public static void run(CancellationToken token, Runnable body) {
        CancellationToken previous = CURRENT.get();
        bind(token);
        try {
            body.run();
        } finally {
            restore(previous);
        }
    }

    /** 显式绑定（传 {@code null} 等价于解绑）。一般走 {@link #call}/{@link #run}，此方法供特殊场景。 */
    public static void bind(CancellationToken token) {
        if (token == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(token);
        }
    }

    /** 解绑当前线程的令牌（ThreadLocal remove，池线程不串味）。 */
    public static void unbind() {
        CURRENT.remove();
    }

    private static void restore(CancellationToken previous) {
        if (previous == null) {
            unbind();
        } else {
            CURRENT.set(previous);
        }
    }
}
