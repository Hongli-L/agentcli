package com.agentcli.rag;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索查询分词器：把一句自然语言拆成可用于 SQL {@code LIKE} 匹配的关键词集合。
 *
 * <p>坚持零新增依赖，因此不引入 jieba 之类的中文分词库，改用规则化拆分：
 * <ul>
 *     <li><b>标点切分</b>：按非字母数字（含中日韩统一表意文字之外的字符）切成片段；</li>
 *     <li><b>驼峰拆分</b>：{@code AuthService} → {@code authservice} / {@code auth} / {@code service}，
 *         这是代码检索区别于普通全文检索的关键——用户问「认证服务」时命中的往往是标识符片段；</li>
 *     <li><b>中文 bigram</b>：中文无空格，按二元切分（{@code 用户登录} → {@code 用户}/{@code 户登}/{@code 登录}），
 *         保证 {@code LIKE '%登录%'} 能命中；</li>
 *     <li><b>停用词与短词过滤</b>：丢弃「怎么/如何/什么」这类无区分度的词与长度小于 2 的片段；</li>
 *     <li><b>上限 8 个 token</b>：关键词过多会让候选集发散，反而稀释语义排序的效果。</li>
 * </ul>
 *
 * <p>包级可见的 final 工具类，仅供 {@link CodeRetriever} 使用。
 */
final class RagQueryTokenizer {

    /** 关键词数量上限，防止候选集发散。 */
    static final int MAX_TOKENS = 8;

    /** 单个 token 的最小长度。 */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** 无区分度的中英文停用词。 */
    private static final Set<String> STOPWORDS = Set.of(
            "怎么", "如何", "什么", "哪些", "哪个", "一下", "实现", "一个", "可以", "这个", "那个",
            "代码", "地方", "是否", "以及", "还有", "帮我", "我想", "请问", "关于",
            "the", "and", "for", "with", "that", "this", "from", "into", "how", "what", "which",
            "where", "when", "who", "why", "are", "was", "were", "is", "of", "in", "to", "on",
            "it", "its", "be", "by", "an", "as", "at", "or", "do", "does");

    private RagQueryTokenizer() {
    }

    /**
     * 把查询串切成关键词集合。
     *
     * @param query 自然语言查询；null / 空白返回空集合
     * @return 有序去重的关键词集合，最多 {@link #MAX_TOKENS} 个，全部小写
     */
    static Set<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        for (String part : query.split("[^\\p{L}\\p{N}_]+")) {
            if (part.isEmpty()) {
                continue;
            }
            splitMixedScript(part, tokens);
        }
        if (tokens.size() <= MAX_TOKENS) {
            return tokens;
        }
        Set<String> limited = new LinkedHashSet<>();
        for (String token : tokens) {
            if (limited.size() >= MAX_TOKENS) {
                break;
            }
            limited.add(token);
        }
        return limited;
    }

    /** 把「中文 + 英文」混排的片段拆成同文种的连续段，分别处理。 */
    private static void splitMixedScript(String part, Set<String> sink) {
        int i = 0;
        while (i < part.length()) {
            boolean cjk = isCjk(part.charAt(i));
            int j = i + 1;
            while (j < part.length() && isCjk(part.charAt(j)) == cjk) {
                j++;
            }
            String run = part.substring(i, j);
            if (cjk) {
                addChineseBigrams(run, sink);
            } else {
                add(run, sink);
                splitCamelCase(run, sink);
            }
            i = j;
        }
    }

    /** 驼峰 / 下划线 / 字母数字边界拆分，每个片段单独入集。 */
    private static void splitCamelCase(String token, Set<String> sink) {
        String[] segments = token.split(
                "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=[0-9])|_+");
        if (segments.length <= 1) {
            return;
        }
        for (String segment : segments) {
            add(segment, sink);
        }
    }

    /** 中文二元切分；长度为 2 的段直接入集。 */
    private static void addChineseBigrams(String part, Set<String> sink) {
        if (part.length() < MIN_TOKEN_LENGTH) {
            return;
        }
        if (part.length() == MIN_TOKEN_LENGTH) {
            add(part, sink);
            return;
        }
        for (int i = 0; i + MIN_TOKEN_LENGTH <= part.length(); i++) {
            add(part.substring(i, i + MIN_TOKEN_LENGTH), sink);
        }
    }

    private static void add(String raw, Set<String> sink) {
        if (raw == null) {
            return;
        }
        String token = raw.trim().toLowerCase();
        if (token.length() < MIN_TOKEN_LENGTH || isStopword(token)) {
            return;
        }
        sink.add(token);
    }

    private static boolean isStopword(String token) {
        return STOPWORDS.contains(token);
    }

    /** 中日韩统一表意文字（含扩展 A 与兼容区）判定。 */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION.equals(block);
    }
}
