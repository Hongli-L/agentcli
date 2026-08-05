package com.agentcli.agent.concurrent;

import com.agentcli.agent.orchestration.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentTaskExecutor} 的并发边界、降级语义、取消回收与线程生命周期。
 *
 * <p>全部时序断言走 {@link CountDownLatch}，不使用 {@code Thread.sleep} 做时间假设——
 * 后者在 CI 的负载抖动下必然偶发翻车。
 */
class AgentTaskExecutorTest {

    private static AgentTask task(String id) {
        return new AgentTask(id, "任务 " + id, AgentRole.WORKER, new CancellationToken());
    }

    @Test
    void submitReturnsResultOfBody() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(2)) {
            AgentTask task = task("task_1");

            String value = executor.submit(task, () -> "ok", 5_000L).get(5, TimeUnit.SECONDS);

            assertEquals("ok", value);
            assertEquals(AgentTaskState.DONE, task.state());
            assertEquals("ok", task.result());
        }
    }

    @Test
    void failingBodyDegradesInsteadOfThrowing() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(2)) {
            AgentTask task = task("task_1");

            CompletableFuture<String> future =
                    executor.submit(task, () -> { throw new IllegalStateException("炸了"); }, 5_000L);
            String value = future.get(5, TimeUnit.SECONDS);

            assertTrue(value.startsWith(AgentTaskExecutor.FAILED_PREFIX),
                    "失败必须收敛为降级文本而不是异常完成，否则 allOf().join() 会整批崩");
            assertTrue(value.contains("炸了"));
            assertEquals(AgentTaskState.FAILED, task.state());
            assertFalse(future.isCompletedExceptionally());
        }
    }

    @Test
    void partialFailureDoesNotBreakAllOfJoin() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(3)) {
            AgentTask ok1 = task("task_1");
            AgentTask bad = task("task_2");
            AgentTask ok2 = task("task_3");

            List<CompletableFuture<String>> futures = List.of(
                    executor.submit(ok1, () -> "A", 5_000L),
                    executor.submit(bad, () -> { throw new RuntimeException("中间那个挂了"); }, 5_000L),
                    executor.submit(ok2, () -> "C", 5_000L));

            // 这一行是「部分失败降级」的技术前提：不能抛
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            assertEquals("A", futures.get(0).join());
            assertTrue(futures.get(1).join().startsWith(AgentTaskExecutor.FAILED_PREFIX));
            assertEquals("C", futures.get(2).join());
            assertEquals(AgentTaskState.DONE, ok1.state());
            assertEquals(AgentTaskState.FAILED, bad.state());
            assertEquals(AgentTaskState.DONE, ok2.state());
        }
    }

    @Test
    void timeoutMarksTaskAndReclaimsThread() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(2)) {
            AgentTask task = task("task_1");
            CountDownLatch interrupted = new CountDownLatch(1);

            CompletableFuture<String> future = executor.submit(task, () -> {
                try {
                    // 永远等不到的闩：只能被 L2 中断唤醒
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                return "never";
            }, 200L);

            assertEquals(AgentTaskExecutor.TIMEOUT_RESULT, future.get(5, TimeUnit.SECONDS));
            assertEquals(AgentTaskState.TIMEOUT, task.state());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                    "orTimeout 只让 future 完成，必须补 raw.cancel(true) 才能真正回收线程");
            assertTrue(task.token().isCancelled(), "超时回收要同时触发 L1/L3");
        }
    }

    @Test
    void cancelInterruptsRunningTask() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(2)) {
            AgentTask task = task("task_7");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);

            executor.submit(task, () -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                return "never";
            }, 30_000L);

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(executor.cancel("task_7"));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "L2 中断必须落在真实池 Future 上");
            assertEquals(AgentTaskState.CANCELLED, task.state());
            assertTrue(task.token().isCancelled());
        }
    }

    @Test
    void cancelUnknownIdReturnsFalseAndCancelAllCountsHits() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(3)) {
            CountDownLatch entered = new CountDownLatch(2);
            for (String id : List.of("task_1", "task_2")) {
                executor.submit(task(id), () -> {
                    entered.countDown();
                    new CountDownLatch(1).await();
                    return "never";
                }, 30_000L);
            }
            AgentTask finished = task("task_3");
            executor.submit(finished, () -> "done", 5_000L).get(5, TimeUnit.SECONDS);

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(executor.cancel("task_404"), "未注册的 id 必须返回 false");
            assertFalse(executor.cancel("   "));

            assertEquals(2, executor.cancelAll(), "已终态的任务不计入取消条数");
            assertEquals(0, executor.cancelAll(), "重复取消是幂等 no-op");
        }
    }

    @Test
    void concurrencyNeverExceedsPoolSize() throws Exception {
        int poolSize = 3;
        int taskCount = 9;
        try (AgentTaskExecutor executor = new AgentTaskExecutor(poolSize)) {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            CountDownLatch arrived = new CountDownLatch(poolSize);
            CountDownLatch release = new CountDownLatch(1);
            List<CompletableFuture<String>> futures = new ArrayList<>();

            for (int i = 1; i <= taskCount; i++) {
                futures.add(executor.submit(task("task_" + i), () -> {
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    arrived.countDown();
                    try {
                        release.await();
                    } finally {
                        active.decrementAndGet();
                    }
                    return "ok";
                }, 30_000L));
            }

            assertTrue(arrived.await(5, TimeUnit.SECONDS), "应恰好有 poolSize 个任务进入执行");
            assertTrue(active.get() <= poolSize, "同时运行的任务数不得超过池尺寸");
            release.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);

            assertEquals(poolSize, peak.get(), "并发峰值应恰好等于池尺寸");
        }
    }

    @Test
    void snapshotIsOrderedAndClearFinishedDropsTerminalOnly() throws Exception {
        try (AgentTaskExecutor executor = new AgentTaskExecutor(2)) {
            executor.submit(task("task_1"), () -> "A", 5_000L).get(5, TimeUnit.SECONDS);
            executor.submit(task("task_2"), () -> "B", 5_000L).get(5, TimeUnit.SECONDS);

            List<AgentTaskView> views = executor.snapshot();
            assertEquals(2, views.size());
            assertEquals("task_1", views.get(0).id(), "快照按创建时间升序，显示顺序稳定");
            assertEquals("task_2", views.get(1).id());
            assertTrue(views.get(0).isDone());

            assertEquals(2, executor.taskCount());
            executor.clearFinished();
            assertEquals(0, executor.taskCount(), "终态任务应被清出注册表，避免无限增长");
        }
    }

    @Test
    void submitAfterCloseIsRejected() {
        AgentTaskExecutor executor = new AgentTaskExecutor(1);
        executor.close();

        assertTrue(executor.isClosed());
        assertThrows(IllegalStateException.class,
                () -> executor.submit(task("task_1"), () -> "x", 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTaskExecutor(1).submit(null, () -> "x", 1_000L));
    }

    @Test
    void closeLeavesNoLiveOrchestratorThreads() throws Exception {
        int before = countOrchestratorThreads();
        AgentTaskExecutor executor = new AgentTaskExecutor(3);
        CountDownLatch entered = new CountDownLatch(3);
        for (int i = 1; i <= 3; i++) {
            executor.submit(task("task_" + i), () -> {
                entered.countDown();
                new CountDownLatch(1).await();
                return "never";
            }, 30_000L);
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(countOrchestratorThreads() >= before + 3, "池线程应已创建并带上命名前缀");

        executor.close();

        // close 内部已 awaitTermination，理论上返回即归零；给 JVM 一点回收余量后再取样
        long deadline = System.currentTimeMillis() + 5_000L;
        int after = countOrchestratorThreads();
        while (after > before && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
            after = countOrchestratorThreads();
        }
        assertEquals(before, after, "close 后不得残留编排线程（线程泄漏红线）");
    }

    /** 统计当前存活的编排池线程数，用于线程泄漏检测。 */
    private static int countOrchestratorThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(AgentTaskExecutor.THREAD_NAME_PREFIX)) {
                count++;
            }
        }
        return count;
    }
}
