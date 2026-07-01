package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBudgetTest {

    @Test
    void detectsStagnantToolCalls() {
        AgentBudget budget = new AgentBudget(10_000, 3, 50);
        List<LlmClient.ToolCall> same = List.of(toolCall("read_file", "{\"path\":\"a.txt\"}"));

        budget.beginIteration();
        budget.recordToolCalls(same);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.beginIteration();
        budget.recordToolCalls(same);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.beginIteration();
        budget.recordToolCalls(same);
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
        assertTrue(budget.describeExit(AgentBudget.ExitReason.STAGNATION_DETECTED).contains("死循环"));
    }

    @Test
    void differentToolCallsResetStagnation() {
        AgentBudget budget = new AgentBudget(10_000, 3, 50);
        budget.beginIteration();
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"a.txt\"}")));
        budget.beginIteration();
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"b.txt\"}")));
        budget.beginIteration();
        budget.recordToolCalls(List.of(toolCall("grep_code", "{\"pattern\":\"foo\"}")));
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void tokenBudgetExceeded() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.beginIteration();
        budget.recordTokens(60, 50);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void hardIterationLimit() {
        AgentBudget budget = new AgentBudget(10_000, 3, 2);
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    private static LlmClient.ToolCall toolCall(String name, String args) {
        return new LlmClient.ToolCall("tc", new LlmClient.ToolCall.Function(name, args));
    }
}
