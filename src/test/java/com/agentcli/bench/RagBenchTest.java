package com.agentcli.bench;

import com.agentcli.common.AppConfig;
import com.agentcli.memory.MemoryEntry;
import com.agentcli.rag.CodeIndex;
import com.agentcli.rag.CodeRetriever;
import com.agentcli.rag.EmbeddingClient;
import com.agentcli.rag.EmbeddingClientFactory;
import com.agentcli.rag.VectorStore;
import com.agentcli.store.SqliteStore;
import com.agentcli.tool.search.CodeSearchEngine;
import com.agentcli.tool.search.RipgrepCodeSearchEngine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R0-3 检索质量与成本基准：{@code grep_code}（关键词） vs {@code rag_search}（语义 + 关键词混合）。
 *
 * <p><b>为什么标注集用本仓库自己的源码</b>：不依赖任何外部数据集，面试时可以当场 clone 后一键复跑；
 * 期望命中文件都是仓库里真实存在的路径，任何人都能自己核对答案对不对。
 *
 * <p><b>两条路径的口径对齐</b>（否则对比没有意义）：
 * <ul>
 *     <li>检索范围都限定在 {@code src/main/java/com/agentcli}，路径一律折算成相对该目录的片段；</li>
 *     <li>两边都收敛为「最多 5 个不同文件」的有序列表，判定「期望文件是否进入 top-5」；</li>
 *     <li>问题原文完全一致，关键词路径的 query 由同一套规则从问题里自动切词得到——
 *         <b>不允许人工为某条问题挑一个「刚好能命中」的关键词</b>，否则基线会被悄悄抬高。</li>
 * </ul>
 *
 * <p><b>依赖分层</b>：{@code grep_code} 路径纯本地、<b>不需要任何 key</b>，必须能独立跑出准确率基线；
 * {@code rag_search} 路径需要 embedding key（走 {@code AppConfig} → {@code EnvLoader} 的既有 .env 机制），
 * 缺 key 时整段跳过并打印提示，<b>绝不因此让测试失败</b>。
 *
 * <p><b>红线</b>：本类只负责「跑出来什么就记什么」。跑不出的指标一律写 {@code ⏳ 待 key}，
 * 任何情况下都不向 {@code bench/result.md} 写入未实测的数字。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("R0-3 检索基准 · grep_code 关键词 vs rag_search 语义混合（标注集取自本仓库源码）")
class RagBenchTest {

    // ---------------------------------------------------------------- 常量与标注集

    /** 样本量：10 条「问题 → 期望命中文件」。 */
    private static final int SAMPLE_SIZE = 10;

    /** 命中口径：两条路径都收敛到「最多 5 个不同文件」。 */
    private static final int TOP_K = 5;

    /** 单条查询最多使用的关键词数；过多会让候选集发散，反而稀释排序信号。 */
    private static final int MAX_QUERY_TOKENS = 6;

    /** 单次注入内容的字符预算，对齐 {@code grep_code} 的默认 {@code max_chars}。 */
    private static final int INJECTION_CHAR_BUDGET = 24_000;

    /** 单个关键词的全局命中上限，取得比工具默认值大，避免热词把长尾文件挤没。 */
    private static final int GREP_MAX_RESULTS = 1_000;

    /** 单个关键词在单文件内的命中上限，保证命中分布在文件之间铺开而不是堆在一个文件里。 */
    private static final int GREP_HEAD_LIMIT = 3;

    /** 命中前后各带几行上下文，用于成本估算时还原「工具实际回灌给模型的文本」。 */
    private static final int GREP_CONTEXT_LINES = 2;

    /** 缺索引时是否自动建索引（会产生真实 embedding API 调用）；{@code -Dagentcli.bench.rag.index=false} 可关闭。 */
    private static final String AUTO_INDEX_PROPERTY = "agentcli.bench.rag.index";

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path SOURCE_ROOT = PROJECT_ROOT.resolve("src").resolve("main")
            .resolve("java").resolve("com").resolve("agentcli");
    private static final Path BENCH_DIR = PROJECT_ROOT.resolve("bench");
    private static final Path RESULT_FILE = BENCH_DIR.resolve("result.md");

    /** 期望路径统一以 {@code src/main/java/com/agentcli/} 为基准，便于人工核对。 */
    private static final String SOURCE_PREFIX = "src/main/java/com/agentcli/";

    /** 无区分度的中英文停用词，与 RAG 侧分词器保持同一口径。 */
    private static final Set<String> STOPWORDS = Set.of(
            "怎么", "如何", "什么", "哪些", "哪个", "一下", "实现", "一个", "可以", "这个", "那个",
            "代码", "地方", "是否", "以及", "还有", "帮我", "我想", "请问", "关于", "在哪",
            "the", "and", "for", "with", "that", "this", "from", "into", "how", "what", "which",
            "where", "when", "who", "why", "are", "was", "were", "is", "of", "in", "to", "on",
            "it", "its", "be", "by", "an", "as", "at", "or", "do", "does");

    /**
     * 10 条标注集。
     *
     * <p>每条的期望文件都已用文件系统核实存在；路径相对 {@code src/main/java/com/agentcli/}。
     * 注意 ROADMAP 里写的 {@code mcp/client/*} 与 {@code lsp/client/*} 是早期设想的包结构，
     * 仓库实际落地为 {@code mcp/McpClient.java} 与 {@code lsp/LspClient.java}，此处以实际路径为准。
     */
    private static final List<BenchCase> CASES = List.of(
            new BenchCase("ReAct 循环在哪实现", "agent/ReActAgent.java"),
            new BenchCase("MCP 客户端怎么连", "mcp/McpClient.java"),
            new BenchCase("SSRF 围栏怎么实现的", "web/NetworkPolicy.java"),
            new BenchCase("审计日志装饰器", "audit/AuditLog.java"),
            new BenchCase("三层取消模型", "agent/concurrent/CancellationToken.java"),
            new BenchCase("RAG 向量索引", "rag/CodeRetriever.java"),
            new BenchCase("LSP 客户端", "lsp/LspClient.java"),
            new BenchCase("SQLite 存储", "store/SqliteStore.java"),
            new BenchCase("Prompt 分层", "agent/SystemPrompt.java"),
            new BenchCase("多 Agent 并行编排", "agent/orchestration/AgentOrchestrator.java"));

    // ---------------------------------------------------------------- 跨用例共享的测量结果

    /** 关键词路径测量结果；未执行时为 {@code null}。 */
    private static PathRun grepRun = null;

    /** 语义路径测量结果；未执行时为 {@code null}。 */
    private static PathRun ragRun = null;

    /** 成本估算结果；未执行时为 {@code null}。 */
    private static CostRun costRun = null;

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    @DisplayName("① grep_code 关键词检索 top-5 命中率（纯本地，无需任何 key，准确率基线）")
    void grepCodeTopFiveHitRate() {
        System.out.println();
        System.out.println("=== ① grep_code 关键词检索基线（样本量 " + SAMPLE_SIZE + "） ===");
        System.out.println("检索根目录: " + SOURCE_ROOT);

        CodeSearchEngine engine = new RipgrepCodeSearchEngine(CodeSearchEngine.EXCLUDED_DIRS);
        List<CaseResult> results = new ArrayList<>(CASES.size());
        String engineName = "unknown";

        for (BenchCase benchCase : CASES) {
            List<String> tokens = keywordsOf(benchCase.question());
            GrepAggregation aggregation = grepSearch(engine, tokens);
            engineName = aggregation.engine();
            List<String> topFiles = aggregation.topFiles(TOP_K);
            boolean hit = isHit(topFiles, benchCase.expectedPath());
            int injectedTokens = MemoryEntry.estimateTokens(aggregation.injectionText(topFiles));
            results.add(new CaseResult(benchCase, hit, topFiles, tokens, injectedTokens));
            printCase(benchCase, hit, topFiles, tokens);
        }

        grepRun = PathRun.executed("grep_code（关键词）", "engine=" + engineName, results);
        System.out.printf(Locale.ROOT, "→ top-%d 命中率: %d/%d = %.1f%%（engine=%s）%n",
                TOP_K, grepRun.hitCount(), SAMPLE_SIZE, grepRun.hitRatePercent(), engineName);

        assertEquals(SAMPLE_SIZE, results.size(), "标注集必须逐条评测完毕");
    }

    @Test
    @Order(2)
    @DisplayName("② rag_search 语义 + 关键词混合 top-5 命中率（需 embedding key，缺 key 整段跳过）")
    void ragSearchTopFiveHitRate() {
        System.out.println();
        System.out.println("=== ② rag_search 语义混合检索（样本量 " + SAMPLE_SIZE + "） ===");

        AppConfig config = AppConfig.load();
        EmbeddingClient embedding = EmbeddingClientFactory.create(config);
        if (!embedding.isAvailable()) {
            String reason = "⏳ rag_search 需 LLM key，已跳过";
            System.out.println(reason + "（原因: " + embedding.unavailableReason() + "）");
            System.out.println("   配置 EMBEDDING_API_KEY / EMBEDDING_BASE_URL / EMBEDDING_MODEL 后重跑即可产出该列数字");
            ragRun = PathRun.skipped("rag_search（语义 + 混合）", reason);
            return;
        }

        System.out.println("embedding 可用: model=" + embedding.modelName() + " baseUrl=" + embedding.baseUrl());
        CodeRetriever retriever;
        VectorStore vectorStore;
        try {
            vectorStore = new VectorStore(SqliteStore.defaultStore());
            retriever = new CodeRetriever(vectorStore, embedding,
                    new RipgrepCodeSearchEngine(CodeSearchEngine.EXCLUDED_DIRS));
        } catch (Exception e) {
            String reason = "⏳ 语义索引库不可用，已跳过（" + brief(e) + "）";
            System.out.println(reason);
            ragRun = PathRun.skipped("rag_search（语义 + 混合）", reason);
            return;
        }

        // 用 SOURCE_ROOT 而不是仓库根做 projectKey：既让返回路径天然对齐标注集，
        // 又不会覆盖用户在仓库根上用 /index 建好的正式索引。
        String projectKey = VectorStore.projectKey(SOURCE_ROOT);
        if (retriever.stats(projectKey).chunkCount() == 0) {
            if (!Boolean.parseBoolean(System.getProperty(AUTO_INDEX_PROPERTY, "true"))) {
                String reason = "⏳ 无语义索引且已关闭自动建索引（-D" + AUTO_INDEX_PROPERTY + "=false），已跳过";
                System.out.println(reason);
                ragRun = PathRun.skipped("rag_search（语义 + 混合）", reason);
                return;
            }
            System.out.println("🧱 未检测到语义索引，开始为基准建索引（会产生真实 embedding API 调用）...");
            CodeIndex.IndexResult indexResult = new CodeIndex(vectorStore, embedding)
                    .index(SOURCE_ROOT, message -> System.out.println("   " + message));
            if (!indexResult.success() || retriever.stats(projectKey).chunkCount() == 0) {
                String reason = "⏳ 建索引未产出向量块，已跳过（" + indexResult.message() + "）";
                System.out.println(reason);
                ragRun = PathRun.skipped("rag_search（语义 + 混合）", reason);
                return;
            }
        }

        VectorStore.IndexStats stats = retriever.stats(projectKey);
        System.out.println("索引就绪: " + stats.chunkCount() + " 块 / " + stats.fileCount() + " 文件 / 模型 "
                + stats.embeddingModel() + " / " + stats.embeddingDim() + " 维");

        List<CaseResult> results = new ArrayList<>(CASES.size());
        boolean anyDegraded = false;
        for (BenchCase benchCase : CASES) {
            // 多取一倍候选再折叠成 top-5「不同文件」，与关键词路径的口径对齐
            CodeRetriever.SearchOutcome outcome =
                    retriever.search(projectKey, SOURCE_ROOT, benchCase.question(), TOP_K * 2);
            anyDegraded = anyDegraded || outcome.degraded();

            List<String> topFiles = new ArrayList<>();
            StringBuilder injection = new StringBuilder();
            for (VectorStore.SearchResult result : outcome.results()) {
                String path = normalizePath(result.filePath());
                if (topFiles.size() < TOP_K && !topFiles.contains(path)) {
                    topFiles.add(path);
                }
                if (topFiles.contains(path)) {
                    appendBounded(injection, "// " + path + "\n" + safe(result.content()) + "\n");
                }
            }
            boolean hit = isHit(topFiles, benchCase.expectedPath());
            results.add(new CaseResult(benchCase, hit, topFiles, List.of(),
                    MemoryEntry.estimateTokens(injection.toString())));
            printCase(benchCase, hit, topFiles, List.of());
        }

        String note = "index=" + stats.chunkCount() + " 块, model=" + stats.embeddingModel()
                + (anyDegraded ? ", ⚠️ 存在降级为关键词兜底的查询" : ", 全程语义混合未降级");
        ragRun = PathRun.executed("rag_search（语义 + 混合）", note, results);
        System.out.printf(Locale.ROOT, "→ top-%d 命中率: %d/%d = %.1f%%（%s）%n",
                TOP_K, ragRun.hitCount(), SAMPLE_SIZE, ragRun.hitRatePercent(), note);

        assertEquals(SAMPLE_SIZE, results.size(), "标注集必须逐条评测完毕");
    }

    @Test
    @Order(3)
    @DisplayName("③ 上下文成本对比：整库全量注入 vs 检索式 top-5 注入（估算 token）")
    void contextCostComparison() throws IOException {
        System.out.println();
        System.out.println("=== ③ 上下文成本估算 ===");

        long fullTokens = 0L;
        long fullChars = 0L;
        int fileCount = 0;
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            List<Path> javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : javaFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                fullTokens += MemoryEntry.estimateTokens(content);
                fullChars += content.length();
                fileCount++;
            }
        }

        // 优先用语义路径的注入量；没跑成就退回关键词路径，并如实标注数据来源
        PathRun source = (ragRun != null && ragRun.executed()) ? ragRun : grepRun;
        String sourceLabel;
        double avgInjection;
        if (source == null || !source.executed()) {
            sourceLabel = "⏳ 无可用检索路径";
            avgInjection = 0.0;
        } else {
            sourceLabel = source.label();
            avgInjection = source.avgInjectedTokens();
        }

        costRun = new CostRun(fullTokens, fileCount, fullChars, sourceLabel, avgInjection);

        System.out.printf(Locale.ROOT, "全量注入基线: %d 个 .java / %d 字符 / 约 %d tokens%n",
                fileCount, fullChars, fullTokens);
        if (costRun.measurable()) {
            System.out.printf(Locale.ROOT, "检索式注入  : 约 %.0f tokens / 问（来源: %s，样本 %d 条）%n",
                    avgInjection, sourceLabel, SAMPLE_SIZE);
            System.out.printf(Locale.ROOT, "→ 单次提问上下文 token 降低: %.1f%%（%d → %.0f）%n",
                    costRun.reductionPercent(), fullTokens, avgInjection);
        } else {
            System.out.println("检索式注入  : ⏳ 待检索路径跑通后补齐");
        }

        assertTrue(fileCount > 0, "源码根目录必须能扫到 .java 文件: " + SOURCE_ROOT);
    }

    @Test
    @Order(4)
    @DisplayName("④ 打印对比表并把实测结果写入 bench/result.md（跑不出的指标一律写占位）")
    void printTableAndWriteResultMarkdown() throws IOException {
        System.out.println();
        System.out.println(renderConsoleTable());

        Files.createDirectories(BENCH_DIR);
        Files.writeString(RESULT_FILE, renderMarkdown(), StandardCharsets.UTF_8);
        System.out.println("📄 已写入: " + RESULT_FILE);

        assertTrue(Files.exists(RESULT_FILE), "基准结果文件必须落盘");
        assertTrue(Files.size(RESULT_FILE) > 0L, "基准结果文件不得为空");
    }

    // ---------------------------------------------------------------- 关键词路径实现

    /**
     * 把一句自然语言问题切成检索关键词。
     *
     * <p>规则与 {@code com.agentcli.rag.RagQueryTokenizer} 一致（标点切分 + 驼峰拆分 + 中文 bigram
     * + 停用词过滤）。之所以在基准里重写一份而不是复用，是因为那个类是包级可见的内部实现，
     * 基准不应该为了跑分去放宽主源码的可见性。
     *
     * @param question 问题原文
     * @return 有序去重的小写关键词，最多 {@link #MAX_QUERY_TOKENS} 个
     */
    private static List<String> keywordsOf(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        if (question == null || question.isBlank()) {
            return List.of();
        }
        for (String part : question.split("[^\\p{L}\\p{N}_]+")) {
            if (!part.isEmpty()) {
                splitMixedScript(part, tokens);
            }
        }
        List<String> limited = new ArrayList<>(MAX_QUERY_TOKENS);
        for (String token : tokens) {
            if (limited.size() >= MAX_QUERY_TOKENS) {
                break;
            }
            limited.add(token);
        }
        return limited;
    }

    /** 把「中文 + 英文」混排片段按文种切开，分别做 bigram / 驼峰拆分。 */
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
                addToken(run, sink);
                for (String segment : run.split(
                        "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=[0-9])|_+")) {
                    addToken(segment, sink);
                }
            }
            i = j;
        }
    }

    private static void addChineseBigrams(String part, Set<String> sink) {
        if (part.length() < 2) {
            return;
        }
        for (int i = 0; i + 2 <= part.length(); i++) {
            addToken(part.substring(i, i + 2), sink);
        }
    }

    private static void addToken(String raw, Set<String> sink) {
        if (raw == null) {
            return;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (token.length() >= 2 && !STOPWORDS.contains(token)) {
            sink.add(token);
        }
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }

    /**
     * 逐关键词跑一遍代码搜索引擎，按「命中的不同关键词数 → 总命中条数 → 路径字典序」给文件排名。
     *
     * <p>用「关键词覆盖度」而不是「命中条数」做主排序键，是为了避免某个高频词
     * （比如「取消」）把一个大文件顶到第一位——覆盖度更接近人找代码时的判断。
     *
     * @param engine 与 {@code grep_code} 工具同款的检索引擎（rg 优先，不可用自动降级纯 Java）
     * @param tokens 查询关键词
     * @return 聚合后的排名与片段
     */
    private static GrepAggregation grepSearch(CodeSearchEngine engine, List<String> tokens) {
        Map<String, FileStat> perFile = new LinkedHashMap<>();
        String engineName = "unknown";
        for (String token : tokens) {
            CodeSearchEngine.CodeSearchRequest request = new CodeSearchEngine.CodeSearchRequest(
                    token, SOURCE_ROOT, PROJECT_ROOT, "**/*.java", false, false,
                    GREP_CONTEXT_LINES, GREP_MAX_RESULTS, GREP_HEAD_LIMIT);
            CodeSearchEngine.CodeSearchResult result = engine.search(request);
            if (result == null || result.matches() == null) {
                continue;
            }
            engineName = result.engine();
            for (CodeSearchEngine.GrepMatch match : result.matches()) {
                String path = normalizePath(match.file());
                if (path.isEmpty()) {
                    continue;
                }
                FileStat stat = perFile.computeIfAbsent(path, key -> new FileStat());
                stat.tokens.add(token);
                stat.matchCount++;
                if (match.context() != null) {
                    for (CodeSearchEngine.ContextLine line : match.context()) {
                        stat.lines.putIfAbsent(line.lineNumber(), safe(line.text()));
                    }
                }
            }
        }
        return new GrepAggregation(engineName, perFile);
    }

    // ---------------------------------------------------------------- 通用工具

    /**
     * 判定期望文件是否落在 top-N 列表里。
     *
     * @param topFiles     相对 {@code src/main/java/com/agentcli/} 的候选路径
     * @param expectedPath 标注集里的期望路径；以 {@code /} 结尾时按目录前缀判定
     */
    private static boolean isHit(List<String> topFiles, String expectedPath) {
        String expected = expectedPath.toLowerCase(Locale.ROOT);
        for (String file : topFiles) {
            String candidate = file.toLowerCase(Locale.ROOT);
            if (expected.endsWith("/") ? candidate.startsWith(expected) : candidate.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /** 统一成「相对 {@code src/main/java/com/agentcli/} 的正斜杠路径」。 */
    private static String normalizePath(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String normalized = rawPath.replace('\\', '/');
        int index = normalized.indexOf(SOURCE_PREFIX);
        if (index >= 0) {
            return normalized.substring(index + SOURCE_PREFIX.length());
        }
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }

    /** 追加文本但不超过注入预算，超出即静默截断（模拟工具的 max_chars 行为）。 */
    private static void appendBounded(StringBuilder sink, String text) {
        int remaining = INJECTION_CHAR_BUDGET - sink.length();
        if (remaining <= 0) {
            return;
        }
        sink.append(text.length() <= remaining ? text : text.substring(0, remaining));
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() > 120 ? single.substring(0, 120) + "..." : single;
    }

    private static void printCase(BenchCase benchCase, boolean hit, List<String> topFiles, List<String> tokens) {
        System.out.printf(Locale.ROOT, "  %s 「%s」 期望=%s%n",
                hit ? "✅" : "❌", benchCase.question(), benchCase.expectedPath());
        if (!tokens.isEmpty()) {
            System.out.println("     keywords: " + String.join(", ", tokens));
        }
        System.out.println("     top-" + TOP_K + ": " + (topFiles.isEmpty() ? "（无命中）" : String.join(", ", topFiles)));
    }

    // ---------------------------------------------------------------- 报告渲染

    /** 控制台对比表：一眼能看出哪列是实测、哪列在等 key。 */
    private static String renderConsoleTable() {
        String grepCell = cellOf(grepRun);
        String ragCell = cellOf(ragRun);
        StringBuilder sb = new StringBuilder();
        sb.append("=== R0-3 基准对比表（样本量 ").append(SAMPLE_SIZE).append("） ===\n");
        sb.append(String.format(Locale.ROOT, "%-26s | %-22s | %-22s%n", "指标", "grep_code", "rag_search"));
        sb.append("-".repeat(78)).append('\n');
        sb.append(String.format(Locale.ROOT, "%-26s | %-22s | %-22s%n",
                "top-" + TOP_K + " 命中率", grepCell, ragCell));
        sb.append(String.format(Locale.ROOT, "%-26s | %-22s | %-22s%n",
                "平均注入 tokens/问",
                grepRun != null && grepRun.executed()
                        ? String.format(Locale.ROOT, "%.0f", grepRun.avgInjectedTokens()) : "⏳ 未跑",
                ragRun != null && ragRun.executed()
                        ? String.format(Locale.ROOT, "%.0f", ragRun.avgInjectedTokens()) : "⏳ 待 key"));
        sb.append("-".repeat(78)).append('\n');
        if (costRun != null) {
            sb.append(String.format(Locale.ROOT, "%-26s | %d tokens（%d 个 .java）%n",
                    "整库全量注入基线", costRun.fullContextTokens(), costRun.fullContextFiles()));
            sb.append(String.format(Locale.ROOT, "%-26s | %s%n", "检索式注入（每问）",
                    costRun.measurable()
                            ? String.format(Locale.ROOT, "%.0f tokens，降低 %.1f%%（来源: %s）",
                            costRun.avgInjectionTokens(), costRun.reductionPercent(), costRun.injectionSource())
                            : "⏳ 待检索路径跑通"));
        }
        return sb.toString();
    }

    private static String cellOf(PathRun run) {
        if (run == null) {
            return "⏳ 未运行";
        }
        if (!run.executed()) {
            return "⏳ 待 key";
        }
        return String.format(Locale.ROOT, "%d/%d = %.1f%%", run.hitCount(), SAMPLE_SIZE, run.hitRatePercent());
    }

    /** {@code bench/result.md} 全文；未实测项一律写占位，绝不补数字。 */
    private static String renderMarkdown() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();

        sb.append("# R0-3 检索质量与成本基准 · 实测结果\n\n");
        sb.append("> 本文件由 `src/test/java/com/agentcli/bench/RagBenchTest.java` **自动生成**，"
                + "跑出什么写什么。\n");
        sb.append("> 未实测项一律标 `⏳ 待 key`，**不写占位数字、不引用他人示例值**。\n\n");

        sb.append("## 测量环境\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 机器 | `<待填：CPU 型号 / 内存 / 网络环境>` |\n");
        sb.append("| 操作系统 | ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" / ")
                .append(System.getProperty("os.arch")).append(" / ")
                .append(Runtime.getRuntime().availableProcessors()).append(" 逻辑核 |\n");
        sb.append("| JDK | ").append(System.getProperty("java.version")).append(" |\n");
        sb.append("| embedding 模型 | ").append(ragRun != null && ragRun.executed()
                ? "见下方 rag_search 备注" : "`<待填：跑通后自动记录>` ⏳ 待 key").append(" |\n");
        sb.append("| 对话模型 | `<待填：仅成本换算需要，本基准只统计 token 不折算金额>` |\n");
        sb.append("| 样本量 | ").append(SAMPLE_SIZE).append(" 条「问题 → 期望命中文件」 |\n");
        sb.append("| 运行日期 | ").append(now).append("（脚本自动记录；如需归档请另注 `<日期占位>`） |\n");
        sb.append("| 检索范围 | `src/main/java/com/agentcli`（两条路径完全一致） |\n\n");

        sb.append("## 结果总表\n\n");
        sb.append("| 指标 | grep_code（关键词） | rag_search（语义 + 混合） |\n|---|---|---|\n");
        sb.append("| top-").append(TOP_K).append(" 命中率 | ").append(cellOf(grepRun))
                .append(" | ").append(cellOf(ragRun)).append(" |\n");
        sb.append("| 平均注入 tokens / 问 | ")
                .append(grepRun != null && grepRun.executed()
                        ? String.format(Locale.ROOT, "%.0f", grepRun.avgInjectedTokens()) : "⏳ 未运行")
                .append(" | ")
                .append(ragRun != null && ragRun.executed()
                        ? String.format(Locale.ROOT, "%.0f", ragRun.avgInjectedTokens()) : "⏳ 待 key")
                .append(" |\n");
        sb.append("| 备注 | ").append(runNote(grepRun)).append(" | ").append(runNote(ragRun)).append(" |\n\n");

        if (grepRun != null && grepRun.executed() && ragRun != null && ragRun.executed()) {
            double delta = ragRun.hitRatePercent() - grepRun.hitRatePercent();
            sb.append("**检索准确率收益**：").append(String.format(Locale.ROOT, "%.1f%% → %.1f%%，绝对提升 %.1f 个百分点。",
                    grepRun.hitRatePercent(), ragRun.hitRatePercent(), delta)).append("\n\n");
        } else {
            sb.append("**检索准确率收益**：⏳ 待 key —— `rag_search` 一列跑通后才能给出对比差值，"
                    + "当前只有关键词基线可用。\n\n");
        }

        sb.append("## 上下文成本对比\n\n");
        if (costRun == null) {
            sb.append("⏳ 未运行。\n\n");
        } else {
            sb.append("| 方案 | 估算 token | 说明 |\n|---|---|---|\n");
            sb.append("| 整库全量塞入上下文 | ").append(costRun.fullContextTokens()).append(" | ")
                    .append(costRun.fullContextFiles()).append(" 个 `.java`，共 ")
                    .append(costRun.fullContextChars()).append(" 字符 |\n");
            if (costRun.measurable()) {
                sb.append("| 先检索 top-").append(TOP_K).append(" 再注入 | ")
                        .append(String.format(Locale.ROOT, "%.0f", costRun.avgInjectionTokens()))
                        .append(" | 每问平均，数据来源：").append(costRun.injectionSource()).append(" |\n\n");
                sb.append(String.format(Locale.ROOT,
                        "**单次提问上下文 token 降低 %.1f%%**（%d → %.0f）。%n%n",
                        costRun.reductionPercent(), costRun.fullContextTokens(), costRun.avgInjectionTokens()));
            } else {
                sb.append("| 先检索 top-").append(TOP_K).append(" 再注入 | ⏳ 待检索路径跑通 | — |\n\n");
            }
            sb.append("> token 估算口径：复用 `MemoryEntry.estimateTokens`（中文约 1.5 字/token，"
                    + "其余约 4 字符/token），**不折算金额**，避免把汇率与厂商定价写死进结论。\n\n");
        }

        sb.append("## 标注集与逐条明细\n\n");
        sb.append("标注集固定 ").append(SAMPLE_SIZE)
                .append(" 条，期望文件路径相对 `src/main/java/com/agentcli/`，均已核实真实存在。\n\n");
        sb.append(renderDetail("grep_code（关键词）", grepRun));
        sb.append(renderDetail("rag_search（语义 + 混合）", ragRun));

        sb.append("## 复现方式\n\n");
        sb.append("```bash\n");
        sb.append("# 本项目 pom 默认 skipTests=true，必须显式关掉\n");
        sb.append("mvn -q test -Dtest=RagBenchTest -DskipTests=false\n");
        sb.append("```\n\n");
        sb.append("- `grep_code` 一列**不需要任何 key**，任何人 clone 后都能跑出同样的基线。\n");
        sb.append("- `rag_search` 一列需要 embedding key（`.env` 里的 `EMBEDDING_API_KEY` / "
                + "`EMBEDDING_BASE_URL` / `EMBEDDING_MODEL`，走既有 `EnvLoader` 机制）；\n");
        sb.append("  无 key 时该列整段跳过并打印 `⏳ rag_search 需 LLM key，已跳过`，测试不会失败。\n");
        sb.append("- 首次带 key 运行会先为 `src/main/java/com/agentcli` 建一次语义索引（产生真实 embedding 调用）；\n");
        sb.append("  加 `-Dagentcli.bench.rag.index=false` 可禁止自动建索引。\n\n");

        sb.append("## 口径声明\n\n");
        sb.append("- 两条路径的检索范围、问题原文、top-").append(TOP_K)
                .append(" 判定口径完全一致，唯一差异是检索方式本身。\n");
        sb.append("- 关键词路径的 query 由统一规则（标点切分 + 驼峰拆分 + 中文 bigram + 停用词过滤）"
                + "自动从问题里切出，**没有为任何一条问题人工挑选「刚好能命中」的关键词**。\n");
        sb.append("- 关键词路径的文件排名按「命中的不同关键词数 → 总命中条数 → 路径字典序」，排序完全确定，可复现。\n");
        sb.append("- 语义路径多取一倍候选块后折叠为 top-").append(TOP_K)
                .append(" 个**不同文件**，与关键词路径对齐。\n");
        return sb.toString();
    }

    private static String runNote(PathRun run) {
        if (run == null) {
            return "⏳ 未运行";
        }
        return run.note() == null || run.note().isBlank() ? "—" : run.note();
    }

    private static String renderDetail(String title, PathRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append("\n\n");
        if (run == null) {
            sb.append("⏳ 未运行。\n\n");
            return sb.toString();
        }
        if (!run.executed()) {
            sb.append(run.note()).append("\n\n");
            sb.append("| # | 问题 | 期望命中文件 |\n|---|---|---|\n");
            for (int i = 0; i < CASES.size(); i++) {
                BenchCase benchCase = CASES.get(i);
                sb.append("| ").append(i + 1).append(" | ").append(benchCase.question())
                        .append(" | `").append(benchCase.expectedPath()).append("` |\n");
            }
            sb.append('\n');
            return sb.toString();
        }
        sb.append("| # | 问题 | 期望命中文件 | 命中 | 实际 top-").append(TOP_K).append(" |\n|---|---|---|---|---|\n");
        for (int i = 0; i < run.results().size(); i++) {
            CaseResult result = run.results().get(i);
            sb.append("| ").append(i + 1).append(" | ").append(result.benchCase().question())
                    .append(" | `").append(result.benchCase().expectedPath()).append("` | ")
                    .append(result.hit() ? "✅" : "❌").append(" | ")
                    .append(result.topFiles().isEmpty() ? "（无命中）"
                            : "`" + String.join("`, `", result.topFiles()) + "`")
                    .append(" |\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- 数据结构

    /** 一条标注：问题原文 + 期望命中文件（相对 {@code src/main/java/com/agentcli/}）。 */
    private record BenchCase(String question, String expectedPath) {
    }

    /** 单条标注的评测结果。 */
    private record CaseResult(BenchCase benchCase, boolean hit, List<String> topFiles,
                              List<String> keywords, int injectedTokens) {
    }

    /**
     * 一条检索路径的整体测量结果。
     *
     * @param executed 是否真的跑起来了；false 表示因缺 key 等原因跳过，此时 {@code results} 为空
     * @param note     备注（引擎名 / 索引规模 / 跳过原因）
     */
    private record PathRun(String label, boolean executed, String note, List<CaseResult> results) {

        static PathRun executed(String label, String note, List<CaseResult> results) {
            return new PathRun(label, true, note, List.copyOf(results));
        }

        static PathRun skipped(String label, String reason) {
            return new PathRun(label, false, reason, List.of());
        }

        int hitCount() {
            int hits = 0;
            for (CaseResult result : results) {
                if (result.hit()) {
                    hits++;
                }
            }
            return hits;
        }

        double hitRatePercent() {
            return results.isEmpty() ? 0.0 : hitCount() * 100.0 / results.size();
        }

        double avgInjectedTokens() {
            if (results.isEmpty()) {
                return 0.0;
            }
            long total = 0L;
            for (CaseResult result : results) {
                total += result.injectedTokens();
            }
            return total / (double) results.size();
        }
    }

    /**
     * 成本估算结果。
     *
     * @param injectionSource 检索式注入数据的实际来源路径名，避免把关键词路径的数字冒充成语义路径的
     */
    private record CostRun(long fullContextTokens, int fullContextFiles, long fullContextChars,
                           String injectionSource, double avgInjectionTokens) {

        boolean measurable() {
            return fullContextTokens > 0 && avgInjectionTokens > 0.0;
        }

        double reductionPercent() {
            return measurable() ? (1.0 - avgInjectionTokens / fullContextTokens) * 100.0 : 0.0;
        }
    }

    /** 单文件的关键词命中统计。 */
    private static final class FileStat {
        private final Set<String> tokens = new LinkedHashSet<>();
        private final Map<Integer, String> lines = new TreeMap<>();
        private int matchCount = 0;
    }

    /** 关键词路径的聚合结果：负责排名与还原「工具回灌给模型的文本」。 */
    private record GrepAggregation(String engine, Map<String, FileStat> perFile) {

        List<String> topFiles(int limit) {
            List<Map.Entry<String, FileStat>> entries = new ArrayList<>(perFile.entrySet());
            // 排序键：命中的不同关键词数 ↓ → 总命中条数 ↓ → 路径字典序 ↑（第三个键保证结果完全确定）
            entries.sort((left, right) -> {
                int byCoverage = Integer.compare(right.getValue().tokens.size(), left.getValue().tokens.size());
                if (byCoverage != 0) {
                    return byCoverage;
                }
                int byMatchCount = Integer.compare(right.getValue().matchCount, left.getValue().matchCount);
                return byMatchCount != 0 ? byMatchCount : left.getKey().compareTo(right.getKey());
            });
            List<String> files = new ArrayList<>(limit);
            for (Map.Entry<String, FileStat> entry : entries) {
                if (files.size() >= limit) {
                    break;
                }
                files.add(entry.getKey());
            }
            return files;
        }

        String injectionText(List<String> files) {
            StringBuilder sb = new StringBuilder();
            for (String file : files) {
                FileStat stat = perFile.get(file);
                if (stat == null) {
                    continue;
                }
                appendBounded(sb, "// " + file + "\n");
                for (Map.Entry<Integer, String> line : stat.lines.entrySet()) {
                    appendBounded(sb, line.getKey() + " | " + line.getValue() + "\n");
                }
            }
            return sb.toString();
        }
    }
}
