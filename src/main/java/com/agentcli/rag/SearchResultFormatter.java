package com.agentcli.rag;

import java.util.List;
import java.util.Locale;

/**
 * {@code /search} 结果排版器。
 *
 * <p>终端里一次只能看十几行，所以排版的目标不是「信息全」而是「一眼定位」：
 * 每条结果压缩成「序号 + 类型 + 块名 + 精确行号区间 + 分数」一行，
 * 下面跟一行折叠后的代码片段（≤ {@value #SNIPPET_MAX_CHARS} 字符）。
 * 想看全文用行号区间打开文件即可。
 *
 * <p>降级提示以单行 {@code ⚠️} 呈现，位置固定在标题与结果计数之间，
 * 让用户第一时间知道「这次结果是关键词兜底出来的，不是语义命中」。
 */
public final class SearchResultFormatter {

    /** 代码片段最大字符数。 */
    public static final int SNIPPET_MAX_CHARS = 120;

    private SearchResultFormatter() {
    }

    /**
     * 排版一次检索的完整输出。
     *
     * @param query   原始查询串
     * @param outcome 检索结果；null 视为空结果
     * @return 多行文本，不以换行结尾
     */
    public static String format(String query, CodeRetriever.SearchOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 检索: ").append(query == null ? "" : query.trim());

        if (outcome == null) {
            sb.append('\n').append("📭 未找到相关代码");
            return sb.toString();
        }

        String degradeMessage = outcome.degradeMessage();
        if (outcome.degraded() && degradeMessage != null && !degradeMessage.isBlank()) {
            sb.append('\n').append(degradeMessage);
        }

        List<VectorStore.SearchResult> results = outcome.results();
        if (results == null || results.isEmpty()) {
            sb.append('\n').append("📭 未找到相关代码");
            return sb.toString();
        }

        sb.append('\n').append("📋 找到 ").append(results.size()).append(" 条结果：").append('\n');
        int index = 1;
        for (VectorStore.SearchResult result : results) {
            sb.append('\n').append(formatOne(index, result));
            index++;
        }
        return sb.toString();
    }

    /**
     * 空结果的极简排版（调用方已确定无需展示降级信息时使用）。
     */
    public static String formatEmpty(String query) {
        return "🔍 检索: " + (query == null ? "" : query.trim()) + "\n📭 未找到相关代码";
    }

    /** 单条结果：标题行 + 缩进片段行。 */
    private static String formatOne(int index, VectorStore.SearchResult result) {
        String type = result.chunkType() == null ? "chunk" : result.chunkType();
        String name = result.name() == null ? "" : result.name();
        String path = result.filePath() == null ? "" : result.filePath();
        String header = String.format(Locale.ROOT, "%2d. [%s] %s  (%s:%d-%d)  score %.2f",
                index, type, name, path, result.startLine(), result.endLine(), result.score());
        String snippet = snippet(result.content(), SNIPPET_MAX_CHARS);
        return snippet.isEmpty() ? header : header + "\n    " + snippet;
    }

    /** 折叠换行、压缩连续空白，并截断到上限。 */
    private static String snippet(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String folded = content.replaceAll("\\s+", " ").trim();
        if (folded.length() <= maxChars) {
            return folded;
        }
        return folded.substring(0, maxChars) + " ...";
    }
}
