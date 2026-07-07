package com.agentcli.memory;

import com.agentcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetTest {

    @Test
    void compressionTriggerIsFractionOfAvailableWindow() {
        TokenBudget budget = new TokenBudget(10_000, 500, 800, 2000);
        int available = budget.getAvailableForConversation();
        assertEquals(6700, available);
        assertEquals((int) (available * 0.75), budget.compressionTriggerTokens());
    }

    @Test
    void needsCompactionWhenMessagesExceedTrigger() {
        TokenBudget budget = new TokenBudget(1_000, 100, 100, 200);
        String longText = "x".repeat(800);
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("sys"),
                LlmClient.Message.user(longText)
        );
        assertTrue(budget.needsCompaction(messages));
    }

    @Test
    void shortHistoryDoesNotNeedCompaction() {
        TokenBudget budget = new TokenBudget(128_000);
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("sys"),
                LlmClient.Message.user("hi")
        );
        assertFalse(budget.needsCompaction(messages));
    }
}
