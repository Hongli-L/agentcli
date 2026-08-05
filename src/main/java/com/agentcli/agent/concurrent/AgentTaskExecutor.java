package com.agentcli.agent.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发任务执行器门面：固定线程池 + 任务注册表 + 三层取消 + 单任务超时回收。
 *
 * <p><b>为什么线程池与 CompletableFuture 两者都用</b>：
 * <ul>
 *   <li>{@link ExecutorService} 负责<b>资源边界</b>——固定并发上限、daemon 线程、可观测的命名前缀
 *       {@value #THREAD_NAME_PREFIX}、优雅关停；</li>
 *   <li>{@link CompletableFuture} 负责<b>编排语义</b>——{@code allOf} 等批、{@code handle} 吞异常做降级、
 *       {@code orTimeout} 表达单任务预算。</li>
 * </ul>
 *
 * <p><b>关键坑位一：{@code CompletableFuture.cancel(true)} 不会中断线程。</b>
 * JDK 明确说明该实现「不使用中断来控制处理」。因此 L2 中断必须落在
 * {@link ExecutorService#submit(Runnable)} 返回的真实 {@link Future} 上，本类把它单独存进
 * {@link Registration#raw}。
 *
 * <p><b>关键坑位二：{@code orTimeout} 只让 future 以 {@link TimeoutException} 完成，
 * 不会停止底层线程。</b> 必须在超时分支里补 {@code token.cancel()}（L1 + L3 关 socket）与
 * {@code rawFuture.cancel(true)}（L2 中断），任务才算真正回收。
 *
 * <p><b>降级语义</b>：任何异常都在 {@code handle} 里被转换成降级文本而<b>不</b>向外抛，
 * 这样上层 {@code CompletableFuture.allOf(...).join()} 不会因为单个任务失败而整批崩掉，
 * 「1 失败 2 成功」才能走部分失败降级聚合。
 *
 * <p>线程安全：注册表为 {@link ConcurrentHashMap}，状态迁移走 {@link AgentTask} 的 CAS。
 */
public final class AgentTaskExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskExecutor.class);

    /** 池线程名前缀，线程泄漏检测据此统计。 */
    public static final String THREAD_NAME_PREFIX = "agentcli-orchestrator-";

    /** 取消任务的降级返回值。 */
    public static final String CANCELLED_RESULT = "⏹️ 已取消当前任务。";

    /** 超时任务的降级返回值。 */
    public static final String TIMEOUT_RESULT = "⏱️ 任务超时，已回收。";

    /** 失败任务的降级返回值前缀。 */
    public static final String FAILED_PREFIX = "❌ 任务失败: ";

    private static final long CLOSE_AWAIT_SECONDS = 5L;

    private final ExecutorService pool;
    private final ConcurrentHashMap<String, Registration> registry = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final int maxParallel;

    public AgentTaskExecutor(int maxParallel) {
        this.maxParallel = Math.max(1, maxParallel);
        this.pool = Executors.newFixedThreadPool(this.maxParallel, namedDaemonThreadFactory());
    }

    /** 并发上限（即池尺寸）。 */
    public int maxParallel() {
        return maxParallel;
    }

    /** 执行器是否已关闭。 */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 提交一个任务。
     *
     * <p>任务体在池线程上执行，执行前把 {@code task.token()} 绑定到 {@link CancellationScope}，
     * {@code finally} 中必定解绑，池线程不会残留上一个任务的令牌。
     *
     * @param timeoutMs 单任务超时预算（毫秒），{@code <= 0} 表示不设预算
     * @return 永不异常完成的 future：失败/取消/超时都被转换成降级文本
     * @throws IllegalStateException 执行器已关闭时拒绝提交
     */
    public CompletableFuture<String> submit(AgentTask task, Callable<String> body, long timeoutMs) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        if (body == null) {
            throw new IllegalArgumentException("body 不能为 null");
        }
        if (closed.get()) {
            throw new IllegalStateException("编排执行器已关闭，拒绝提交任务: " + task.id());
        }

        CompletableFuture<String> outcome = new CompletableFuture<>();
        Registration registration = new Registration(task, outcome);
        registry.put(task.id(), registration);

        try {
            Future<?> raw = pool.submit(() -> runBody(task, body, outcome));
            registration.raw.set(raw);
            // 补偿「submit 与 cancel 交错」：若 raw 落库前任务已被取消，这里补一刀 L2
            if (task.state().isTerminal()) {
                raw.cancel(true);
            }
        } catch (RejectedExecutionException e) {
            task.markCancelled();
            outcome.complete(CANCELLED_RESULT);
        }

        if (timeoutMs > 0) {
            // orTimeout 返回的就是 outcome 自身，到点会让它以 TimeoutException 异常完成
            outcome.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        }
        return outcome.handle((value, error) -> settle(task, value, error));
    }

    /**
     * 精确取消一个任务（三层同时下手）。
     *
     * @return true 表示命中了一个非终态任务并已请求取消
     */
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        Registration registration = registry.get(taskId.trim());
        return registration != null && cancelOne(registration);
    }

    /** 取消全部非终态任务，返回实际命中的条数。 */
    public int cancelAll() {
        int count = 0;
        for (Registration registration : registry.values()) {
            if (cancelOne(registration)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 只读快照，{@code /tasks} 的数据源。
     *
     * <p>遍历 {@link ConcurrentHashMap} 逐个取 {@link AgentTask#toView()}，不加锁、不阻塞编排；
     * 按创建时间升序排列，保证同一次编排内的任务显示顺序稳定。
     */
    public List<AgentTaskView> snapshot() {
        List<AgentTask> tasks = new ArrayList<>(registry.size());
        for (Registration registration : registry.values()) {
            tasks.add(registration.task);
        }
        tasks.sort(Comparator.comparingLong(AgentTask::createdAt).thenComparing(AgentTask::id));
        List<AgentTaskView> views = new ArrayList<>(tasks.size());
        for (AgentTask task : tasks) {
            views.add(task.toView());
        }
        return List.copyOf(views);
    }

    /** 当前注册的任务数（含已完成）。 */
    public int taskCount() {
        return registry.size();
    }

    /** 清空任务注册表（会话内多次编排时避免快照无限增长）。 */
    public void clearFinished() {
        registry.entrySet().removeIf(entry -> entry.getValue().task.state().isTerminal());
    }

    /**
     * 关停：先取消全部在跑任务，再 {@code shutdown} → {@code awaitTermination(5s)} → 必要时
     * {@code shutdownNow}。
     *
     * <p>验收口径：本方法返回后，名字以 {@value #THREAD_NAME_PREFIX} 开头且存活的线程数必须为 0。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelAll();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("编排线程池未能在 {}s 内完全关停", CLOSE_AWAIT_SECONDS * 2);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /** 池线程上的任务体：绑定令牌 → 执行 → 落地终态 → 解绑。 */
    private void runBody(AgentTask task, Callable<String> body, CompletableFuture<String> outcome) {
        if (task.token().isCancelled()) {
            task.markCancelled();
            outcome.complete(CANCELLED_RESULT);
            return;
        }
        if (!task.markRunning()) {
            // 派发前已被取消或已进入终态
            outcome.complete(fallbackText(task));
            return;
        }
        try {
            String value = CancellationScope.call(task.token(), body);
            if (task.token().isCancelled()) {
                task.markCancelled();
                outcome.complete(CANCELLED_RESULT);
            } else if (task.markDone(value)) {
                outcome.complete(task.result());
            } else {
                // 终态已被超时/取消抢先落地，first-writer-wins
                outcome.complete(fallbackText(task));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.markCancelled();
            outcome.complete(CANCELLED_RESULT);
        } catch (Exception e) {
            String message = describe(e);
            task.markFailed(message);
            outcome.complete(FAILED_PREFIX + message);
        } finally {
            // 三件套之一：ThreadLocal 必须 remove，池线程复用时不串味
            CancellationScope.unbind();
        }
    }

    /**
     * 在 {@code handle} 中收敛所有完成路径：超时补刀回收，异常转降级文本。
     *
     * <p>本方法<b>绝不</b>重新抛出异常——上层 {@code allOf().join()} 依赖它来实现部分失败降级。
     */
    private String settle(AgentTask task, String value, Throwable error) {
        Registration registration = registry.get(task.id());
        if (error == null) {
            releaseRaw(registration);
            return value == null ? fallbackText(task) : value;
        }
        Throwable cause = unwrap(error);
        if (cause instanceof TimeoutException) {
            task.markTimeout();
            reclaim(task, registration);
            return TIMEOUT_RESULT;
        }
        if (cause instanceof InterruptedException) {
            task.markCancelled();
            reclaim(task, registration);
            return CANCELLED_RESULT;
        }
        String message = describe(cause);
        task.markFailed(message);
        releaseRaw(registration);
        return FAILED_PREFIX + message;
    }

    /** 超时/中断后的真实回收：L1 协作标志 + L3 句柄取消 + L2 线程中断。 */
    private void reclaim(AgentTask task, Registration registration) {
        task.token().cancel();
        if (registration != null) {
            Future<?> raw = registration.raw.getAndSet(null);
            if (raw != null) {
                raw.cancel(true);
            }
        }
    }

    private boolean cancelOne(Registration registration) {
        AgentTask task = registration.task;
        if (task.state().isTerminal()) {
            return false;
        }
        // L1 协作标志 + L3 已注册句柄（如 okhttp Call.cancel）
        task.token().cancel();
        boolean marked = task.markCancelled();
        // L2 线程中断：兜住 sleep / BlockingQueue.take / Future.get 等可中断点
        Future<?> raw = registration.raw.get();
        if (raw != null) {
            raw.cancel(true);
        }
        registration.outcome.complete(CANCELLED_RESULT);
        return marked;
    }

    private void releaseRaw(Registration registration) {
        if (registration != null) {
            registration.raw.set(null);
        }
    }

    private static String fallbackText(AgentTask task) {
        return switch (task.state()) {
            case DONE -> task.result() == null ? "" : task.result();
            case TIMEOUT -> TIMEOUT_RESULT;
            case CANCELLED -> CANCELLED_RESULT;
            case FAILED -> FAILED_PREFIX + (task.error() == null ? "未知错误" : task.error());
            default -> "";
        };
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static ThreadFactory namedDaemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 任务注册项：实体 + 对外 future + 真实池 future（L2 中断的唯一抓手）。 */
    private static final class Registration {

        private final AgentTask task;
        private final CompletableFuture<String> outcome;
        private final AtomicReference<Future<?>> raw = new AtomicReference<>();

        private Registration(AgentTask task, CompletableFuture<String> outcome) {
            this.task = task;
            this.outcome = outcome;
        }
    }
}
