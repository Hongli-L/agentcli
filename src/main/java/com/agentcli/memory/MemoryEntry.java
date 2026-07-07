package com.agentcli.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目：长期记忆与摘要的基础数据单元。
 */
public class MemoryEntry {

    public enum MemoryType {
        FACT,
        SUMMARY
    }

    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.tokenCount = tokenCount;
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public MemoryType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    /** 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }
}
