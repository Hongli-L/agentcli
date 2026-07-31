package com.agentcli.memory;

import com.agentcli.llm.LlmClient;

import java.util.List;

/**
 * Token 预算与消息列表 token 估算。
 */
public class TokenBudget {

    private static final double DEFAULT_COMPRESSION_TRIGGER_RATIO = 0.75;

    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
    }

    public static TokenBudget forLlmClient(LlmClient client) {
        int window = client == null ? 128_000 : client.maxContextWindow();
        return new TokenBudget(window);
    }

    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    public int compressionTriggerTokens() {
        return compressionTriggerTokens(DEFAULT_COMPRESSION_TRIGGER_RATIO);
    }

    public int compressionTriggerTokens(double triggerRatio) {
        return (int) (getAvailableForConversation() * triggerRatio);
    }

    public boolean needsCompaction(List<LlmClient.Message> messages) {
        return needsCompaction(messages, DEFAULT_COMPRESSION_TRIGGER_RATIO);
    }

    public boolean needsCompaction(List<LlmClient.Message> messages, double triggerRatio) {
        return estimateMessagesTokens(messages) >= compressionTriggerTokens(triggerRatio);
    }

    public void recordUsage(int inputTokens, int outputTokens) {
        recordUsage(inputTokens, outputTokens, 0);
    }

    public void recordUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        totalInputTokens += Math.max(0, inputTokens);
        totalOutputTokens += Math.max(0, outputTokens);
        totalCachedInputTokens += Math.max(0, cachedInputTokens);
        llmCallCount++;
    }

    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | cached: %d | 平均输入: %.0f | 窗口: %d (对话可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, totalCachedInputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getTotalInputTokens() {
        return totalInputTokens;
    }

    public int getTotalOutputTokens() {
        return totalOutputTokens;
    }

    /** 会话内累计的缓存命中输入 token（计费单价低于普通输入）。 */
    public int getTotalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    /** 会话内累计的 LLM 调用次数。 */
    public int getLlmCallCount() {
        return llmCallCount;
    }

    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (LlmClient.Message msg : messages) {
            total += MemoryEntry.estimateTokens(msg.content());
            if (msg.toolCalls() != null) {
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    total += MemoryEntry.estimateTokens(tc.function().arguments());
                }
            }
        }
        total += messages.size() * 4;
        return total;
    }
}
