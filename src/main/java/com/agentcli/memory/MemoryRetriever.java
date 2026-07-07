package com.agentcli.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从长期记忆中检索与当前查询相关的事实，用于注入 system prompt。
 */
public class MemoryRetriever {

    private final LongTermMemory longTermMemory;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        return longTermMemory.getAll().stream()
                .filter(entry -> LongTermMemory.isVisibleInProject(entry, projectKey))
                .map(entry -> new ScoredEntry(entry, computeRelevanceScore(entry, query)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
        if (relevant.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder("## 相关长期记忆\n\n");
        int usedTokens = MemoryEntry.estimateTokens(context.toString());
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) {
                break;
            }
            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }
        context.append("\n");
        return context.toString();
    }

    private double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();
        if (contentLower.contains(queryLower)) {
            return 1.0;
        }
        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        if (queryWords.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (String word : queryWords) {
            if (contentLower.contains(word)) {
                matched++;
            }
        }
        if (matched == 0) {
            return 0;
        }
        double keywordScore = (double) matched / queryWords.size();
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);
        return keywordScore * timeDecay;
    }

    private record ScoredEntry(MemoryEntry entry, double score) {
    }
}
