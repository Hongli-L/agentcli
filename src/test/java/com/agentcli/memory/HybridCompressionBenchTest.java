package com.agentcli.memory;

import com.agentcli.llm.LlmClient;
import com.agentcli.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HYBRID 压缩基准（无网络、确定性）：长对话经三层混合压缩后，量化「历史 token 占用降低百分比」。
 * 该数字即简历「分层记忆体系，长对话 Token 降低 30%+」的实测出处（以本测试打印值为准）。
 */
class HybridCompressionBenchTest {

    @Test
    void longConversationTokenReduction() throws Exception {
        Path tmp = Files.createTempFile("hybrid-bench", ".db");
        LongTermMemory ltm = new LongTermMemory(SqliteStore.open(tmp));
        ltm.store(new MemoryEntry("f1", "项目使用 Java 17 与 Maven 构建",
                MemoryEntry.MemoryType.FACT, Map.of("source", "bench", "scope", "global"),
                MemoryEntry.estimateTokens("项目使用 Java 17 与 Maven 构建")));
        ltm.store(new MemoryEntry("f2", "并发编排基于 JUC 线程池与 CompletableFuture",
                MemoryEntry.MemoryType.FACT, Map.of("source", "bench", "scope", "global"),
                MemoryEntry.estimateTokens("并发编排基于 JUC 线程池与 CompletableFuture")));
        MemoryRetriever retriever = new MemoryRetriever(ltm);

        // 确定性摘要策略（离线），避免依赖真实 LLM，保证基准可复现
        HybridMemoryCompressor compressor = new HybridMemoryCompressor(
                new HybridMemoryCompressor.LocalExtractiveSummaryStrategy(), retriever);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("你是一个 Java 编程助手"));
        for (int i = 0; i < 24; i++) {
            String u = ("第 " + i + " 轮：请帮我重构模块 " + i + " 的异常处理逻辑，确保覆盖空指针与数组越界，并补充单元测试。").repeat(4);
            String a = ("已重构模块 " + i + "，增加了 null 检查与边界断言，并补充了 3 个测试用例覆盖异常路径。").repeat(4);
            history.add(LlmClient.Message.user(u));
            history.add(LlmClient.Message.assistant(a));
        }

        int baseline = TokenBudget.estimateMessagesTokens(history);
        compressor.compactNow(history, "如何重构异常处理", "global");
        int hybrid = TokenBudget.estimateMessagesTokens(history);

        double reduction = (1.0 - (double) hybrid / baseline) * 100.0;
        System.out.println("[HYBRID BENCH] baseline=" + baseline + " tokens, hybrid=" + hybrid
                + " tokens, reduction=" + String.format("%.1f", reduction) + "%");
        assertTrue(reduction >= 30.0, "HYBRID 压缩应至少降低 30% token，实际 " + reduction + "%");
    }
}
