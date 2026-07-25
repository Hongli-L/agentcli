package com.agentcli.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PLAN_JSON = """
            {
              "summary": "demo plan",
              "tasks": [
                {"id":"1","description":"read source","type":"FILE_READ","dependencies":[]},
                {"id":"2","description":"analyze","type":"ANALYSIS","dependencies":["1"]},
                {"id":"3","description":"verify","type":"VERIFICATION","dependencies":["1"]}
              ]
            }""";

    @Test
    void parsesTasksAndRewritesIds() throws IOException {
        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "goal", PLAN_JSON);
        assertEquals(3, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_1") != null);
        assertEquals("read source", plan.getTask("task_1").getDescription());
    }

    @Test
    void respectsDependencyTopologicalOrder() throws IOException {
        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "goal", PLAN_JSON);
        List<String> order = plan.getExecutionOrder();
        assertEquals("task_1", order.get(0));
        // task_2 and task_3 both depend only on task_1, so they follow it
        assertTrue(order.indexOf("task_2") > order.indexOf("task_1"));
        assertTrue(order.indexOf("task_3") > order.indexOf("task_1"));
    }

    @Test
    void executableTasksUnlockAsDependenciesComplete() throws IOException {
        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "goal", PLAN_JSON);
        List<Task> first = plan.getExecutableTasks();
        assertEquals(1, first.size());
        assertEquals("task_1", first.get(0).getId());

        plan.getTask("task_1").markCompleted("done");
        List<Task> next = plan.getExecutableTasks();
        assertEquals(2, next.size());
        assertTrue(next.stream().anyMatch(t -> t.getId().equals("task_2")));
        assertTrue(next.stream().anyMatch(t -> t.getId().equals("task_3")));
    }

    @Test
    void progressTracksCompletedRatio() throws IOException {
        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "goal", PLAN_JSON);
        assertEquals(0.0, plan.getProgress(), 0.001);
        plan.getTask("task_1").markCompleted("a");
        assertEquals(1.0 / 3, plan.getProgress(), 0.001);
        plan.getTask("task_2").markCompleted("b");
        plan.getTask("task_3").markCompleted("c");
        assertEquals(1.0, plan.getProgress(), 0.001);
        assertTrue(plan.isAllCompleted());
    }

    @Test
    void cycleDetectionThrows() {
        String cycle = """
                {
                  "summary": "cycle",
                  "tasks": [
                    {"id":"1","description":"a","type":"COMMAND","dependencies":["2"]},
                    {"id":"2","description":"b","type":"COMMAND","dependencies":["1"]}
                  ]
                }""";
        assertThrows(IOException.class, () -> ExecutionPlan.fromJson(MAPPER, "goal", cycle));
    }

    @Test
    void summarizeAndVisualizeProduceText() throws IOException {
        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "goal", PLAN_JSON);
        assertFalse(plan.summarize().isBlank());
        assertFalse(plan.visualize().isBlank());
    }
}
