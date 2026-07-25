package com.agentcli.plan;

import com.agentcli.llm.LlmClient;
import com.agentcli.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private static final String PLAN_JSON = """
            {
              "summary": "multi-step",
              "tasks": [
                {"id":"1","description":"read code","type":"FILE_READ","dependencies":[]},
                {"id":"2","description":"write tests","type":"FILE_WRITE","dependencies":["1"]}
              ]
            }""";

    @Test
    void createPlanParsesLlmJson() throws Exception {
        StubLlmClient client = new StubLlmClient("glm", "zhipu");
        client.enqueue(new LlmClient.ChatResponse("assistant", PLAN_JSON, null, null, 0, 0));

        Planner planner = new Planner(client);
        ExecutionPlan plan = planner.createPlan("先阅读代码然后编写单元测试覆盖核心逻辑");

        assertEquals(1, client.chatCalls());
        assertEquals(2, plan.getAllTasks().size());
        assertEquals("read code", plan.getTask("task_1").getDescription());
        assertTrue(plan.getStatus() == ExecutionPlan.PlanStatus.CREATED);
    }

    @Test
    void simpleGoalProducesSingleTaskWithoutLlm() throws Exception {
        StubLlmClient client = new StubLlmClient("glm", "zhipu");
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("列出当前目录文件");

        assertEquals(0, client.chatCalls());
        assertEquals(1, plan.getAllTasks().size());
        assertEquals("列出当前目录文件", plan.getTask("task_1").getDescription());
    }
}
