package com.agentcli.rag;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentcli.tool.search.CodeSearchEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 混合检索器：语义向量 + 关键词双路召回，并在任何一环不可用时优雅降级到 Ripgrep。
 *
 * <p>为什么要混合：纯向量检索对「精确标识符」很弱（问 {@code AuthService.login} 时余弦分未必最高），
 * 纯关键词检索又答不了「用户登录是怎么实现的」这种自然语言问题。两路并起来、
 * 双路命中的块额外加分，才能同时覆盖两类查询意图。
 *
 * <p><b>降级决策树</b>（顺序即优先级，任一命中都走 Ripgrep 兜底）：
 * <ol>
 *     <li>embedding 不可用 → {@link DegradeReason#EMBEDDING_NOT_CONFIGURED}</li>
 *     <li>索引里没有向量块 → {@link DegradeReason#INDEX_MISSING}</li>
 *     <li>查询向量化抛 {@code IOException} → {@link DegradeReason#EMBEDDING_REQUEST_FAILED}</li>
 *     <li>查询向量维度与索引不符 → {@link DegradeReason#INDEX_DIMENSION_MISMATCH}</li>
 * </ol>
 *
 * <p><b>不抛方法</b>：{@link #search}、{@link #relations}、{@link #stats} 全部内部吸收异常，
 * 最坏情况返回空结果，绝不把堆栈捅到用户面前。
 */
public class CodeRetriever {

    private static final Logger log = LoggerFactory.getLogger(CodeRetriever.class);

    /** 默认返回条数。 */
    public static final int DEFAULT_TOP_K = 5;

    /** 同一文件最多保留的结果条数，避免一个大文件霸屏。 */
    private static final int MAX_PER_FILE = 2;

    /** 双路命中奖励。 */
    private static final double DUAL_HIT_BONUS = 0.10;
    /** 关键词命中块名的加分。 */
    private static final double NAME_HIT_BONUS = 0.30;
    /** 关键词命中路径的加分。 */
    private static final double PATH_HIT_BONUS = 0.10;
    /** 关键词命中内容的加分。 */
    private static final double CONTENT_HIT_BONUS = 0.10;
    /** 方法块类型加分。 */
    private static final double METHOD_TYPE_BONUS = 0.15;
    /** 类型块（class/interface/enum/record）加分。 */
    private static final double TYPE_TYPE_BONUS = 0.10;

    private final VectorStore store;
    private final EmbeddingClient embedding;
    private final CodeSearchEngine fallbackEngine;

    /**
     * @param store          向量与关系存储
     * @param embedding      embedding 客户端，永不为 null（不可用时为空对象）
     * @param fallbackEngine 降级检索引擎，通常为 {@code RipgrepCodeSearchEngine}；null 表示不做兜底
     */
    public CodeRetriever(VectorStore store, EmbeddingClient embedding, CodeSearchEngine fallbackEngine) {
        if (store == null || embedding == null) {
            throw new IllegalArgumentException("CodeRetriever 依赖项不得为 null");
        }
        this.store = store;
        this.embedding = embedding;
        this.fallbackEngine = fallbackEngine;
    }

    /**
     * 检索代码。
     *
     * @param projectKey  项目键（{@link VectorStore#projectKey(Path)}）
     * @param projectRoot 项目根目录，降级检索时使用
     * @param query       非空查询串（空串由上层门面提前拦截）
     * @param topK        返回条数，小于等于 0 时取 {@link #DEFAULT_TOP_K}
     * @return 检索结果与降级信息；<b>绝不抛异常</b>
     */
    public SearchOutcome search(String projectKey, Path projectRoot, String query, int topK) {
        int limit = topK <= 0 ? DEFAULT_TOP_K : topK;
        if (query == null || query.isBlank()) {
            return SearchOutcome.semantic(List.of());
        }

        if (!embedding.isAvailable()) {
            return degrade(projectRoot, query, limit,
                    DegradeReason.EMBEDDING_NOT_CONFIGURED, embedding.unavailableReason());
        }

        VectorStore.IndexStats stats = stats(projectKey);
        if (stats.chunkCount() == 0) {
            return degrade(projectRoot, query, limit, DegradeReason.INDEX_MISSING, null);
        }

        float[] queryVector;
        try {
            queryVector = embedding.embed(query);
        } catch (IOException | RuntimeException e) {
            log.warn("查询向量化失败，降级为关键词检索: {}", query, e);
            return degrade(projectRoot, query, limit, DegradeReason.EMBEDDING_REQUEST_FAILED, brief(e));
        }

        if (queryVector == null || queryVector.length != stats.embeddingDim()) {
            return degrade(projectRoot, query, limit, DegradeReason.INDEX_DIMENSION_MISMATCH,
                    stats.embeddingModel());
        }

        try {
            return SearchOutcome.semantic(hybrid(projectKey, query, queryVector, limit));
        } catch (SQLException | RuntimeException e) {
            log.warn("混合检索失败，降级为关键词检索: {}", query, e);
            return degrade(projectRoot, query, limit, DegradeReason.EMBEDDING_REQUEST_FAILED, brief(e));
        }
    }

    /**
     * 查询某个类 / 方法的关系（<b>完全不经过 embedding</b>）。
     *
     * @return 关系列表；读取失败返回空列表
     */
    public List<CodeRelation> relations(String projectKey, String name) {
        if (projectKey == null || name == null || name.isBlank()) {
            return List.of();
        }
        try {
            return store.findRelations(projectKey, name.trim());
        } catch (SQLException | RuntimeException e) {
            log.warn("查询类关系失败: {}", name, e);
            return List.of();
        }
    }

    /** 索引统计；读取失败返回 {@link VectorStore.IndexStats#empty()}。 */
    public VectorStore.IndexStats stats(String projectKey) {
        try {
            return store.stats(projectKey);
        } catch (SQLException | RuntimeException e) {
            log.warn("读取索引统计失败: {}", projectKey, e);
            return VectorStore.IndexStats.empty();
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /** 统一的降级出口：跑一遍 Ripgrep 兜底，并带上降级原因与细节。 */
    private SearchOutcome degrade(Path projectRoot, String query, int topK,
                                  DegradeReason reason, String detail) {
        return new SearchOutcome(Mode.KEYWORD_FALLBACK, reason, detail,
                ripgrepFallback(projectRoot, query, topK));
    }

    /** 语义 + 关键词双路召回与加权排序。 */
    private List<VectorStore.SearchResult> hybrid(String projectKey, String query,
                                                  float[] queryVector, int topK) throws SQLException {
        int candidateLimit = Math.max(topK * 2, 10);
        Map<String, VectorStore.SearchResult> merged = new LinkedHashMap<>();
        Set<String> semanticKeys = new HashSet<>();
        for (VectorStore.SearchResult result : store.searchByVector(projectKey, queryVector, candidateLimit)) {
            merged.put(result.key(), result);
            semanticKeys.add(result.key());
        }

        Set<String> keywordKeys = new HashSet<>();
        for (String token : RagQueryTokenizer.tokenize(query)) {
            for (VectorStore.SearchResult hit : store.searchByKeyword(projectKey, token, candidateLimit)) {
                keywordKeys.add(hit.key());
                VectorStore.SearchResult existing = merged.get(hit.key());
                VectorStore.SearchResult base = existing != null ? existing : hit;
                merged.put(hit.key(), base.withScore(base.score() + boostKeywordMatch(hit, token)));
            }
        }

        List<VectorStore.SearchResult> scored = new ArrayList<>(merged.size());
        for (Map.Entry<String, VectorStore.SearchResult> entry : merged.entrySet()) {
            VectorStore.SearchResult result = entry.getValue();
            double score = result.score() + typeBoost(result.chunkType());
            if (semanticKeys.contains(entry.getKey()) && keywordKeys.contains(entry.getKey())) {
                score += DUAL_HIT_BONUS;
            }
            scored.add(result.withScore(score));
        }
        scored.sort(Comparator.comparingDouble(VectorStore.SearchResult::score).reversed()
                .thenComparing(VectorStore.SearchResult::key));
        return limitPerFile(scored, topK, MAX_PER_FILE);
    }

    /** 关键词命中位置加分：块名 &gt; 路径 = 内容。 */
    private static double boostKeywordMatch(VectorStore.SearchResult result, String token) {
        String keyword = token.toLowerCase();
        double boost = 0.0;
        if (result.name() != null && result.name().toLowerCase().contains(keyword)) {
            boost += NAME_HIT_BONUS;
        }
        if (result.filePath() != null && result.filePath().toLowerCase().contains(keyword)) {
            boost += PATH_HIT_BONUS;
        }
        if (result.content() != null && result.content().toLowerCase().contains(keyword)) {
            boost += CONTENT_HIT_BONUS;
        }
        return boost;
    }

    /** 块类型加分：方法体承载最具体的实现细节，排序时优先。 */
    private static double typeBoost(String chunkType) {
        if (chunkType == null) {
            return 0.0;
        }
        return switch (chunkType) {
            case CodeChunk.TYPE_METHOD -> METHOD_TYPE_BONUS;
            case "class", "interface", "enum", "record" -> TYPE_TYPE_BONUS;
            default -> 0.0;
        };
    }

    /** 同一文件最多保留 {@code maxPerFile} 条，最终截断到 topK。 */
    private static List<VectorStore.SearchResult> limitPerFile(List<VectorStore.SearchResult> sorted,
                                                               int topK, int maxPerFile) {
        List<VectorStore.SearchResult> picked = new ArrayList<>(topK);
        Map<String, Integer> perFile = new HashMap<>();
        for (VectorStore.SearchResult result : sorted) {
            if (picked.size() >= topK) {
                break;
            }
            String file = result.filePath() == null ? "" : result.filePath();
            int used = perFile.getOrDefault(file, 0);
            if (used >= maxPerFile) {
                continue;
            }
            perFile.put(file, used + 1);
            picked.add(result);
        }
        return picked;
    }

    /**
     * Ripgrep 兜底检索。
     *
     * <p>{@code RipgrepCodeSearchEngine} 内部在 rg 不可用时已自动降级到纯 Java 引擎，
     * 因此这里只需再兜一层 {@code RuntimeException}，保证降级路径本身不会二次失败。
     */
    private List<VectorStore.SearchResult> ripgrepFallback(Path projectRoot, String query, int topK) {
        if (fallbackEngine == null || projectRoot == null) {
            return List.of();
        }
        try {
            CodeSearchEngine.CodeSearchRequest request = new CodeSearchEngine.CodeSearchRequest(
                    query, projectRoot, projectRoot, null, false, false, 2, topK * 4, 2);
            CodeSearchEngine.CodeSearchResult result = fallbackEngine.search(request);
            if (result == null || result.matches() == null) {
                return List.of();
            }
            List<VectorStore.SearchResult> results = new ArrayList<>();
            for (CodeSearchEngine.GrepMatch match : result.matches()) {
                if (results.size() >= topK) {
                    break;
                }
                results.add(toSearchResult(projectRoot, match));
            }
            return results;
        } catch (RuntimeException e) {
            log.warn("降级检索失败，返回空结果: {}", query, e);
            return List.of();
        }
    }

    private static VectorStore.SearchResult toSearchResult(Path projectRoot, CodeSearchEngine.GrepMatch match) {
        String relativePath = relativize(projectRoot, match.file());
        int slash = relativePath.lastIndexOf('/');
        String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        StringBuilder text = new StringBuilder();
        if (match.context() != null) {
            for (CodeSearchEngine.ContextLine line : match.context()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line.text());
            }
        }
        return new VectorStore.SearchResult(relativePath, "text", fileName, text.toString(),
                match.lineNumber(), match.lineNumber(), 0.0);
    }

    private static String relativize(Path projectRoot, String file) {
        if (file == null) {
            return "";
        }
        String normalized = file.replace('\\', '/');
        String root = projectRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (normalized.startsWith(root)) {
            String relative = normalized.substring(root.length());
            return relative.startsWith("/") ? relative.substring(1) : relative;
        }
        return normalized;
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replace('\n', ' ').trim();
        return single.length() > 120 ? single.substring(0, 120) + "..." : single;
    }

    // ---------------------------------------------------------------- 嵌套类型

    /** 检索模式。 */
    public enum Mode {
        /** 语义 + 关键词混合检索。 */
        SEMANTIC_HYBRID,
        /** 降级后的纯关键词检索（Ripgrep 兜底）。 */
        KEYWORD_FALLBACK
    }

    /** 降级原因与用户可见文案。 */
    public enum DegradeReason {
        /** 未降级。 */
        NONE(""),
        /** embedding 未配置或配置不完整。 */
        EMBEDDING_NOT_CONFIGURED("⚠️ 语义检索不可用（未配置 embedding），已降级为关键词检索"),
        /** 尚未建立语义索引。 */
        INDEX_MISSING("⚠️ 代码库尚未建立语义索引（请先执行 /index），已降级为关键词检索"),
        /** embedding 请求失败。 */
        EMBEDDING_REQUEST_FAILED("⚠️ embedding 请求失败（%s），已降级为关键词检索"),
        /** 索引维度与当前模型不符。 */
        INDEX_DIMENSION_MISMATCH("⚠️ 索引由 %s 生成，与当前模型维度不符，请重新 /index；本次已降级为关键词检索");

        private final String template;

        DegradeReason(String template) {
            this.template = template;
        }

        /** 不带细节的文案（细节位以通用措辞填充）。 */
        public String message() {
            return message(null);
        }

        /**
         * 带细节的文案。
         *
         * @param detail 原因摘要 / 旧模型名；null 或空白时以通用措辞兜底
         */
        public String message(String detail) {
            if (this == NONE) {
                return "";
            }
            if (!template.contains("%s")) {
                return template;
            }
            String value = detail == null || detail.isBlank()
                    ? (this == INDEX_DIMENSION_MISMATCH ? "其它模型" : "网络或服务不可用")
                    : detail;
            return String.format(template, value);
        }
    }

    /**
     * 检索结果。
     *
     * @param detail 降级细节（原因摘要 / 旧模型名），未降级时为 null；
     *               用于填充第 8.6 节文案中的动态部分
     */
    public record SearchOutcome(Mode mode, DegradeReason degradeReason, String detail,
                                List<VectorStore.SearchResult> results) {

        /** 语义检索成功的结果。 */
        public static SearchOutcome semantic(List<VectorStore.SearchResult> results) {
            return new SearchOutcome(Mode.SEMANTIC_HYBRID, DegradeReason.NONE, null,
                    results == null ? List.of() : results);
        }

        /** 是否发生了降级。 */
        public boolean degraded() {
            return mode == Mode.KEYWORD_FALLBACK;
        }

        /** 降级提示文案；未降级返回空串。 */
        public String degradeMessage() {
            return degradeReason == null ? "" : degradeReason.message(detail);
        }
    }
}
