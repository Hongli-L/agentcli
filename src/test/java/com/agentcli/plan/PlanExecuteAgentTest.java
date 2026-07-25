package com.agentcli.plan;

import com.agentcli.llm.LlmClient;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String THREE_TASK_PLAN = """
            {
              "summary": "demo",
              "tasks": [
                {"id":"1","description":"read source","type":"FILE_READ","dependencies":[]},
                {"id":"2","description":"analyze","type":"ANALYSIS","dependencies":["1"]},
                {"id":"3","description":"verify","type":"VERIFICATION","dependencies":["1"]}
              ]
            }""";

    private static final String TWO_TASK_PLAN = """
            {
              "summary": "demo",
              "tasks": [
                {"id":"1","description":"setup","type":"COMMAND","dependencies":[]},
                {"id":"2","description":"use setup","type":"ANALYSIS","dependencies":["1"]}
              ]
            }""";

    private PlanExecuteAgent agentWithStubExecutor(PlanExecuteAgent.TaskExecutor executor) {
        PlanExecuteAgent agent = new PlanExecuteAgent(new StubLlmClient("m", "p"), new ToolRegistry());
        agent.setTaskExecutor(executor);
        return agent;
    }

    @Test
    void executesAllTasksAndMarksPlanCompleted() throws IOException {
        List<String> prompts = new ArrayList<>();
        PlanExecuteAgent agent = agentWithStubExecutor((prompt) -> {
            prompts.add(prompt);
            return "ok";
        });

        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "build the feature", THREE_TASK_PLAN);
        String result = agent.executePlan(plan);

        assertEquals(3, prompts.size());
        for (Task task : plan.getAllTasks()) {
            assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
        }
        assertEquals(ExecutionPlan.PlanStatus.COMPLETED, plan.getStatus());
        assertTrue(result.contains("successfully"));
        // each task prompt should carry the goal and its own description
        assertTrue(prompts.get(0).contains("build the feature"));
        assertTrue(prompts.get(0).contains("read source"));
    }

    @Test
    void failureMarksPlanFailedWithoutReplanWhenProgressAtHalf() throws IOException {
        List<String> prompts = new ArrayList<>();
        PlanExecuteAgent agent = agentWithStubExecutor((prompt) -> {
            prompts.add(prompt);
            if (prompt.contains("use setup")) {
                throw new IOException("boom");
            }
            return "ok";
        });

        ExecutionPlan plan = ExecutionPlan.fromJson(MAPPER, "goal", TWO_TASK_PLAN);
        String result = agent.executePlan(plan);

        assertEquals(2, prompts.size());
        assertEquals(Task.TaskStatus.COMPLETED, plan.getTask("task_1").getStatus());
        assertEquals(Task.TaskStatus.FAILED, plan.getTask("task_2").getStatus());
        assertEquals(ExecutionPlan.PlanStatus.FAILED, plan.getStatus());
        assertTrue(result.contains("failed"));
    }
}
