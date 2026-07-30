package com.agentcli.mcp.mention;

import java.util.regex.Pattern;

/**
 * 共享常量与内联文本块格式定义，供 {@link MentionResolver} 与测试统一引用。
 *
 * <p>所有 @mention 相关的“魔法值”集中在此，避免散落各处导致不一致。
 */
public final class MentionPolicy {
    private MentionPolicy() {
    }

    /** 单文件内联注入的字节上限（P1-1）：默认 32KB。 */
    public static final int MAX_INLINE_BYTES = 32 * 1024;

    /** 超限截断标记（printf 风格，参数 = 实际字节数）。 */
    public static final String TRUNCATED_MARKER = "[truncated: %d bytes]";

    /**
     * @mention 正则：负向后顾排除 {@code user@host}，捕获组排除空白 / {@code @} / {@code ,} / {@code ;}
     * 以支持逗号分隔的多引用（如 {@code @a.txt,@b.txt}）。
     */
    public static final Pattern MENTION_PATTERN =
            Pattern.compile("(?<!\\p{Alnum})@([^\\s@,;]+)");

    /** 注入失败时插入的占位标记（参数 = @token，原因）。 */
    public static final String MENTION_ERROR_MARKER = "[mention error: %s — %s]";

    /**
     * 构造内联文本块：{@code <file path="relPath">\n内容\n</file>}。
     */
    public static String inlineFormat(String relPath, String content) {
        String safeRel = relPath == null ? "" : relPath;
        String safeContent = content == null ? "" : content;
        return "<file path=\"" + safeRel + "\">\n" + safeContent + "\n</file>";
    }

    /** 生成截断标记文本。 */
    public static String truncatedMarker(long actualBytes) {
        return String.format(TRUNCATED_MARKER, actualBytes);
    }

    /** 生成注入失败标记文本。 */
    public static String mentionError(String token, String reason) {
        return String.format(MENTION_ERROR_MARKER, token, reason == null ? "unknown" : reason);
    }
}
