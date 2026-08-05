package com.agentcli.agent.concurrent;

import com.agentcli.agent.orchestration.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentTask} 状态机与 CAS 并发语义。
 *
 * <p>核心验收点是<b>终态唯一性</b>：超时线程与 worker 线程会天然竞争同一个任务的终态，
 * 必须保证「第一个写入者胜出、后续 mark 幂等失败」，否则 {@code /tasks} 显示的状态会随调度抖动。
 */
class AgentTaskStateMachineTest {

    private static AgentTask newTask() {
        return new AgentTask("task_1", "读取配置文件", AgentRole.WORKER, new CancellationToken());
    }

    @Test
    void newTaskStartsPending() {
        AgentTask task = newTask();

        assertEquals(AgentTaskState.PENDING, task.state());
        assertFalse(task.state().isTerminal());
        assertEquals(0L, task.durationMs(), "未开始的任务耗时应为 0");
        assertNotNull(task.token());
    }

    @Test
    void pendingToRunningToDone() {
        AgentTask task = newTask();

        assertTrue(task.markRunning());
        assertEquals(AgentTaskState.RUNNING, task.state());
        assertTrue(task.markDone("配置已读取"));
        assertEquals(AgentTaskState.DONE, task.state());
        assertEquals("配置已读取", task.result());
        assertTrue(task.state().isTerminal());
    }

    @Test
    void pendingCanBeCancelledBeforeRunning() {
        AgentTask task = newTask();

        assertTrue(task.markCancelled(), "入队后、执行前的取消必须允许");
        assertEquals(AgentTaskState.CANCELLED, task.state());
        assertFalse(task.markRunning(), "已取消的任务不得再进入 RUNNING");
    }

    @Test
    void terminalStateRejectsFurtherTransitions() {
        AgentTask task = newTask();
        task.markRunning();
        assertTrue(task.markDone("结果"));

        assertFalse(task.markFailed("迟到的失败"));
        assertFalse(task.markCancelled());
        assertFalse(task.markTimeout());
        assertEquals(AgentTaskState.DONE, task.state(), "终态不可被覆盖");
        assertEquals("结果", task.result());
    }

    @Test
    void pendingCannotJumpDirectlyToDone() {
        AgentTask task = newTask();

        assertFalse(task.markDone("跳过 RUNNING"), "PENDING 不允许直达 DONE");
        assertEquals(AgentTaskState.PENDING, task.state());
    }

    @Test
    void transitionTableMatchesDocumentedGraph() {
        assertTrue(AgentTaskState.PENDING.canTransitionTo(AgentTaskState.RUNNING));
        assertTrue(AgentTaskState.PENDING.canTransitionTo(AgentTaskState.CANCELLED));
        assertFalse(AgentTaskState.PENDING.canTransitionTo(AgentTaskState.TIMEOUT));
        assertTrue(AgentTaskState.RUNNING.canTransitionTo(AgentTaskState.TIMEOUT));
        assertFalse(AgentTaskState.DONE.canTransitionTo(AgentTaskState.FAILED));
        assertFalse(AgentTaskState.RUNNING.canTransitionTo(AgentTaskState.RUNNING),
                "自迁移无意义，应判非法");
        assertFalse(AgentTaskState.RUNNING.canTransitionTo(null));
    }

    @Test
    void concurrentTerminalWritesElectExactlyOneWinner() throws Exception {
        int threads = 8;
        AgentTask task = newTask();
        task.markRunning();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        boolean won = switch (index % 4) {
                            case 0 -> task.markDone("done-" + index);
                            case 1 -> task.markFailed("failed-" + index);
                            case 2 -> task.markCancelled();
                            default -> task.markTimeout();
                        };
                        if (won) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "并发写入应在 5s 内收敛");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "终态只能被写入一次（first-writer-wins）");
        assertTrue(task.state().isTerminal());
    }

    @Test
    void viewIsAnImmutableSnapshotAndRejectsBlankId() {
        AgentTask task = newTask();
        task.markRunning();
        task.markFailed("磁盘写满了，导致后续所有步骤都无法继续执行下去，这段错误信息故意写得很长很长很长");

        AgentTaskView view = task.toView();
        assertEquals("task_1", view.id());
        assertEquals(AgentTaskState.FAILED, view.state());
        assertFalse(view.isDone());
        assertNotNull(view.errorPreview());
        assertTrue(view.errorPreview().length() <= AgentTaskView.ERROR_PREVIEW_CHARS,
                "错误摘要必须截断，避免 /tasks 刷屏");
        assertTrue(view.summaryLine().contains("task_1"));

        assertThrows(IllegalArgumentException.class,
                () -> new AgentTask("  ", "空 id", AgentRole.WORKER, null));
    }
}
