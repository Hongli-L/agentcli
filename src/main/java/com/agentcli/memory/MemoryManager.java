package com.agentcli.memory;

import com.agentcli.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆系统门面：长期记忆存取、检索注入与 token 统计。
 */
public class MemoryManager {

    private static final int DEFAULT_MEMORY_CONTEXT_TOKENS = 1_500;

    private final LongTermMemory longTermMemory;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private LlmClient llmClient;
    private String currentProject = LongTermMemory.normalizeProjectKey(System.getProperty("user.dir"));

    public MemoryManager(LlmClient llmClient) {
        this(new LongTermMemory(), TokenBudget.forLlmClient(llmClient));
        this.llmClient = llmClient;
    }

    public MemoryManager(LongTermMemory longTermMemory, TokenBudget tokenBudget) {
        this.longTermMemory = longTermMemory;
        this.retriever = new MemoryRetriever(longTermMemory);
        this.tokenBudget = tokenBudget;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.tokenBudget = TokenBudget.forLlmClient(llmClient);
    }

    public void setProjectPath(String projectPath) {
        if (projectPath != null && !projectPath.isBlank()) {
            this.currentProject = LongTermMemory.normalizeProjectKey(projectPath);
        }
    }

    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact.trim(),
                MemoryEntry.MemoryType.FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll(currentProject);
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    public void clearLongTerm() {
        longTermMemory.clear();
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    public String buildContextForQuery(String query) {
        return buildContextForQuery(query, DEFAULT_MEMORY_CONTEXT_TOKENS);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    public TokenBudget getTokenBudget() {
        return tokenBudget;
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public MemoryRetriever getRetriever() {
        return retriever;
    }

    public String getCurrentProjectKey() {
        return currentProject;
    }

    public String getSystemStatus() {
        int estimated = tokenBudget.getAvailableForConversation();
        return longTermMemory.getStatusSummary() + "\n"
                + tokenBudget.getUsageReport() + "\n"
                + "压缩阈值约: " + tokenBudget.compressionTriggerTokens() + " tokens (对话可用 " + estimated + ")";
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        return "global".equalsIgnoreCase(scope.trim()) ? "global" : "project";
    }
}
