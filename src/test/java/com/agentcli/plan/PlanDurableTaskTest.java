package com.agentcli.plan;

import com.agentcli.store.SqliteStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDurableTaskTest {

    @Test
    void persistsAndResumesPlan(@TempDir Path dir) throws Exception {
        SqliteStore store = SqliteStore.open(dir.resolve("agentcli-test.db")); {
            PlanStore planStore = new PlanStore(store);

            ExecutionPlan plan = new ExecutionPlan("plan_x", "实现功能 A");
            Task t1 = new Task("task_1", "读取需求", Task.TaskType.FILE_READ);
            Task t2 = new Task("task_2", "编写代码", Task.TaskType.FILE_WRITE, List.of("task_1"));
            plan.addTask(t1);
            plan.addTask(t2);
            assertTrue(plan.computeExecutionOrder());
            plan.markStarted();
            planStore.savePlan(plan);

            // 完成 task_1 并持久化
            t1.markCompleted("需求已读");
            planStore.saveTask("plan_x", t1);

            ExecutionPlan loaded = planStore.loadPlan("plan_x");
            assertNotNull(loaded);
            assertEquals(ExecutionPlan.PlanStatus.RUNNING, loaded.getStatus());
            assertEquals(Task.TaskStatus.COMPLETED, loaded.getTask("task_1").getStatus());
            assertEquals(Task.TaskStatus.PENDING, loaded.getTask("task_2").getStatus());
            // 依赖关系已恢复
            assertTrue(loaded.getTask("task_2").getDependencies().contains("task_1"));
            // 该 plan 仍可恢复
            assertEquals("plan_x", planStore.findResumablePlanId());

            // 整体完成后不可恢复
            plan.markCompleted();
            planStore.savePlan(plan);
            assertNull(planStore.findResumablePlanId());
        }
    }

    @Test
    void restoresFailedStatusAndResetsOnResume(@TempDir Path dir) throws Exception {
        SqliteStore store = SqliteStore.open(dir.resolve("agentcli-test.db")); {
            PlanStore planStore = new PlanStore(store);
            ExecutionPlan plan = new ExecutionPlan("plan_y", "g");
            Task t1 = new Task("task_1", "do", Task.TaskType.COMMAND);
            plan.addTask(t1);
            plan.markStarted();
            t1.markFailed("boom");
            planStore.savePlan(plan);

            ExecutionPlan loaded = planStore.loadPlan("plan_y");
            assertNotNull(loaded);
            assertEquals(Task.TaskStatus.FAILED, loaded.getTask("task_1").getStatus());

            // 续跑语义：失败任务重置回 PENDING 以便重试
            loaded.getAllTasks().stream()
                    .filter(t -> t.getStatus() == Task.TaskStatus.FAILED)
                    .forEach(t -> t.restoreState(Task.TaskStatus.PENDING, null, null));
            assertEquals(Task.TaskStatus.PENDING, loaded.getTask("task_1").getStatus());
        }
    }

    @Test
    void executePlanPersistsViaPlanStore(@TempDir Path dir) throws Exception {
        SqliteStore store = SqliteStore.open(dir.resolve("agentcli-test.db")); {
            PlanStore planStore = new PlanStore(store);
            PlanExecuteAgent agent = new PlanExecuteAgent(null, null);
            agent.setPlanStore(planStore);
            agent.setTaskExecutor(taskPrompt -> "done:" + taskPrompt);
            agent.setOut(new PrintStream(new ByteArrayOutputStream()));

            ExecutionPlan plan = new ExecutionPlan("plan_z", "g");
            plan.addTask(new Task("task_1", "a", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("task_2", "b", Task.TaskType.ANALYSIS, List.of("task_1")));
            plan.computeExecutionOrder();

            String result = agent.executePlan(plan);
            assertTrue(result.contains("successfully"));

            ExecutionPlan loaded = planStore.loadPlan("plan_z");
            assertNotNull(loaded);
            assertEquals(Task.TaskStatus.COMPLETED, loaded.getTask("task_1").getStatus());
            assertEquals(Task.TaskStatus.COMPLETED, loaded.getTask("task_2").getStatus());
            assertEquals(ExecutionPlan.PlanStatus.COMPLETED, loaded.getStatus());
        }
    }
}
