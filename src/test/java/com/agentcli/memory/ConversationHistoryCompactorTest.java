package com.agentcli.memory;

import com.agentcli.llm.LlmClient;
import com.agentcli.testsupport.StubLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationHistoryCompactorTest {

    private StubLlmClient llm;
    private ConversationHistoryCompactor compactor;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient("stub", "stub");
        compactor = new ConversationHistoryCompactor(llm, 1);
    }

    @Test
    void compactNowSummarizesOlderMessages() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "用户想写文件，已完成写入。", null, List.of(), 10, 5));

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("sys"));
        history.add(LlmClient.Message.user("round1"));
        history.add(LlmClient.Message.assistant("ok1"));
        history.add(LlmClient.Message.user("round2"));
        history.add(LlmClient.Message.assistant("ok2"));

        boolean compacted = compactor.compactNow(history);

        assertTrue(compacted);
        assertTrue(history.stream().anyMatch(m -> m.content() != null && m.content().contains("已压缩的历史对话摘要")));
        assertEquals("round2", history.get(history.size() - 2).content());
    }

    @Test
    void compactIfNeededSkipsWhenBelowTrigger() {
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("sys"),
                LlmClient.Message.user("only one")
        );
        assertFalse(compactor.compactIfNeeded(history, 999_999));
        assertEquals(2, history.size());
    }
}
