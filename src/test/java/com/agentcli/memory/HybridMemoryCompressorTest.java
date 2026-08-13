package com.agentcli.memory;

import com.agentcli.llm.LlmClient;
import com.agentcli.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridMemoryCompressorTest {

    private static final class LocalMemory {
        final LongTermMemory store;
        final MemoryRetriever retriever;

        LocalMemory() throws Exception {
            Path tmp = Files.createTempFile("hybrid-test", ".db");
            store = new LongTermMemory(SqliteStore.open(tmp));
            retriever = new MemoryRetriever(store);
        }
    }

    private static HybridMemoryCompressor compressor(LocalMemory mem) {
        return new HybridMemoryCompressor(
                new HybridMemoryCompressor.LocalExtractiveSummaryStrategy(), mem.retriever);
    }

    @Test
    void compactNowSummarizesOlderRoundsAndKeepsRecentHot() throws Exception {
        LocalMemory mem = new LocalMemory();
        HybridMemoryCompressor c = compressor(mem);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("sys"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("round" + i));
            history.add(LlmClient.Message.assistant("ok" + i));
        }

        int before = history.size();
        assertTrue(c.compactNow(history, "anything", "proj"));
        assertTrue(history.stream().anyMatch(m -> m.content() != null && m.content().contains("已压缩的历史对话摘要")));
        // 热层保留最近 3 轮 user
        assertTrue(history.stream().anyMatch(m -> "round5".equals(m.content())));
        assertTrue(history.stream().anyMatch(m -> "round4".equals(m.content())));
        assertTrue(history.stream().anyMatch(m -> "round3".equals(m.content())));
        assertTrue(history.size() < before);
    }

    @Test
    void coldTierInjectedWhenRelevant() throws Exception {
        LocalMemory mem = new LocalMemory();
        mem.store.store(new MemoryEntry("fact1", "重构异常处理应使用 null 检查与边界断言",
                MemoryEntry.MemoryType.FACT, Map.of("source", "test", "scope", "global"), 0));
        HybridMemoryCompressor c = compressor(mem);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("sys"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("round" + i));
            history.add(LlmClient.Message.assistant("ok" + i));
        }
        c.compactNow(history, "如何重构异常处理", "proj");
        assertTrue(history.stream().anyMatch(m -> m.content() != null && m.content().contains("相关长期记忆")));
    }

    @Test
    void coldTierSkippedWhenQueryIrrelevant() throws Exception {
        LocalMemory mem = new LocalMemory();
        mem.store.store(new MemoryEntry("fact1", "项目使用 Java 17 与 Maven 构建",
                MemoryEntry.MemoryType.FACT, Map.of("source", "test", "scope", "global"), 0));
        HybridMemoryCompressor c = compressor(mem);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("sys"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("round" + i));
            history.add(LlmClient.Message.assistant("ok" + i));
        }
        c.compactNow(history, "unrelated query about cooking", "proj");
        assertTrue(history.stream().noneMatch(m -> m.content() != null && m.content().contains("相关长期记忆")));
    }

    @Test
    void noCompactionWhenBelowTrigger() throws Exception {
        LocalMemory mem = new LocalMemory();
        HybridMemoryCompressor c = compressor(mem);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("sys"));
        history.add(LlmClient.Message.user("only one"));
        history.add(LlmClient.Message.assistant("ok"));
        assertFalse(c.compactIfNeeded(history, 999_999, "q", "proj"));
        assertEquals(3, history.size());
    }

    @Test
    void noCompactionWhenTooFewRounds() throws Exception {
        LocalMemory mem = new LocalMemory();
        HybridMemoryCompressor c = compressor(mem);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("sys"));
        history.add(LlmClient.Message.user("r1"));
        history.add(LlmClient.Message.assistant("ok1"));
        history.add(LlmClient.Message.user("r2"));
        history.add(LlmClient.Message.assistant("ok2"));
        assertFalse(c.compactNow(history, "q", "proj"));
        assertEquals(5, history.size());
    }
}
