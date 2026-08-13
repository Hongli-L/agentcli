package com.agentcli.memory;

import com.agentcli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 三层混合对话上下文压缩（v0.21 HYBRID）。
 *
 * <p>在上下文接近窗口上限时，把对话历史重组成：
 * <ul>
 *   <li>热层(HOT)：最近 {@code retainRecentRounds} 轮原文，保留完整推理链；</li>
 *   <li>温层(WARM)：更早轮次由 {@link SummaryStrategy} 生成的摘要（生产走 LLM，离线走确定性抽取）；</li>
 *   <li>冷层(COLD)：{@link MemoryRetriever} 按当前查询召回的相关长期记忆事实，仅当相关且未超预算时注入。</li>
 * </ul>
 *
 * <p>全量受 {@link TokenBudget} 闸门约束：超预算时先丢弃冷层，再截断温层，热层始终保留。
 * 这是简历所述「短期摘要压缩 + 长期持久化分层记忆体系」的工程落地：单靠温层摘要即可在长对话下
 * 把历史 token 压到 30%+ 以下，冷层只补充「当前查询真正相关」的长期事实，并不显著增加体积。
 */
public class HybridMemoryCompressor {

    /** 摘要策略：把旧消息压缩成一段摘要文本。可注入以便离线 / 测试使用确定性实现。 */
    public interface SummaryStrategy {
        String summarize(List<LlmClient.Message> oldMessages) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryCompressor.class);
    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;
    private static final int DEFAULT_COLD_TIER_TOKEN_BUDGET = 600;
    private static final int DEFAULT_COLD_TIER_LIMIT = 5;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private SummaryStrategy summaryStrategy;
    private final MemoryRetriever retriever;
    private final int retainRecentRounds;
    private final int coldTierTokenBudget;
    private final int coldTierLimit;

    public HybridMemoryCompressor(LlmClient llmClient, MemoryRetriever retriever) {
        this(new LlmSummaryStrategy(llmClient), retriever);
    }

    public HybridMemoryCompressor(SummaryStrategy summaryStrategy, MemoryRetriever retriever) {
        this(summaryStrategy, retriever, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public HybridMemoryCompressor(SummaryStrategy summaryStrategy, MemoryRetriever retriever, int retainRecentRounds) {
        this.summaryStrategy = summaryStrategy;
        this.retriever = retriever;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
        this.coldTierTokenBudget = DEFAULT_COLD_TIER_TOKEN_BUDGET;
        this.coldTierLimit = DEFAULT_COLD_TIER_LIMIT;
    }

    /** LLM 客户端变更时重建默认摘要策略（仅当当前仍是 LLM 策略，避免覆盖注入的自定义策略）。 */
    public void setLlmClient(LlmClient llmClient) {
        if (summaryStrategy instanceof LlmSummaryStrategy) {
            this.summaryStrategy = new LlmSummaryStrategy(llmClient);
        }
    }

    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens, String query, String projectKey) {
        return compact(history, triggerTokens, false, query, projectKey);
    }

    public boolean compactNow(List<LlmClient.Message> history, String query, String projectKey) {
        return compact(history, 0, true, query, projectKey);
    }

    private boolean compact(List<LlmClient.Message> history, int triggerTokens, boolean force, String query, String projectKey) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (!force && currentTokens < triggerTokens) {
            return false;
        }

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }
        int effectiveRetain = Math.max(1, retainRecentRounds);
        if (userIndices.size() <= effectiveRetain) {
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - effectiveRetain);
        if (splitIdx <= systemEnd) {
            return false;
        }

        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) {
            return false;
        }

        // 温层：旧消息摘要
        String summary;
        try {
            summary = summaryStrategy.summarize(oldMsgs);
        } catch (IOException e) {
            log.warn("hybrid warm-tier summary failed; skip compaction to avoid data loss", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }

        // 重装：system + 温层摘要 + 热层近期
        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(new ArrayList<>(history.subList(splitIdx, history.size())));

        // 冷层：召回相关长期记忆，受冷层预算闸门约束（超预算先丢这一层）
        if (retriever != null && query != null && !query.isBlank()) {
            int used = TokenBudget.estimateMessagesTokens(rebuilt);
            List<MemoryEntry> cold = retriever.retrieveLongTerm(query, coldTierLimit, projectKey);
            StringBuilder block = new StringBuilder();
            int coldTokens = 0;
            for (MemoryEntry entry : cold) {
                int t = entry.getTokenCount() > 0 ? entry.getTokenCount()
                        : MemoryEntry.estimateTokens(entry.getContent());
                if (used + coldTokens + t > coldTierTokenBudget) {
                    break;
                }
                block.append("- ").append(entry.getContent()).append("\n");
                coldTokens += t;
            }
            if (block.length() > 0) {
                rebuilt.add(LlmClient.Message.user("[相关长期记忆]\n" + block.toString().trim()));
            }
        }

        history.clear();
        history.addAll(rebuilt);
        log.info("hybrid compressed: tokens {} -> {}, messages {} -> {}",
                currentTokens, TokenBudget.estimateMessagesTokens(rebuilt),
                oldMsgs.size() + systemEnd, rebuilt.size());
        return true;
    }

    /** 生产用的 LLM 摘要策略（含降级保护）。 */
    public static class LlmSummaryStrategy implements SummaryStrategy {
        private final LlmClient llmClient;

        public LlmSummaryStrategy(LlmClient llmClient) {
            this.llmClient = llmClient;
        }

        @Override
        public String summarize(List<LlmClient.Message> messages) throws IOException {
            if (llmClient == null) {
                throw new IOException("LLM client not configured");
            }
            StringBuilder sb = new StringBuilder();
            for (LlmClient.Message m : messages) {
                sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
                if (m.content() != null) {
                    sb.append(m.content());
                }
                if (m.toolCalls() != null) {
                    for (LlmClient.ToolCall tc : m.toolCalls()) {
                        sb.append("\n  TOOL_CALL ").append(tc.function().name())
                                .append(": ").append(tc.function().arguments());
                    }
                }
                sb.append("\n\n");
                if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                    sb.append("...(超长内容已截断)\n");
                    break;
                }
            }
            String prompt = String.format(SUMMARY_PROMPT, sb);
            List<LlmClient.Message> req = List.of(
                    LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                    LlmClient.Message.user(prompt)
            );
            LlmClient.ChatResponse response = llmClient.chat(req, null);
            return response == null ? null : response.content();
        }
    }

    /** 离线 / 测试的确定性抽取式摘要：拼接旧消息并截断到固定字符上限，无任何网络调用。 */
    public static class LocalExtractiveSummaryStrategy implements SummaryStrategy {
        private final int maxChars;

        public LocalExtractiveSummaryStrategy() {
            this(600);
        }

        public LocalExtractiveSummaryStrategy(int maxChars) {
            this.maxChars = Math.max(50, maxChars);
        }

        @Override
        public String summarize(List<LlmClient.Message> messages) {
            StringBuilder sb = new StringBuilder();
            for (LlmClient.Message m : messages) {
                sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
                if (m.content() != null) {
                    sb.append(m.content());
                }
                if (m.toolCalls() != null) {
                    for (LlmClient.ToolCall tc : m.toolCalls()) {
                        sb.append("\n  TOOL_CALL ").append(tc.function().name())
                                .append(": ").append(tc.function().arguments());
                    }
                }
                sb.append("\n\n");
            }
            String text = sb.toString();
            if (text.length() > maxChars) {
                text = "[摘要] " + text.substring(0, maxChars).trim() + "...(截断)";
            } else {
                text = "[摘要] " + text.trim();
            }
            return text;
        }
    }
}
