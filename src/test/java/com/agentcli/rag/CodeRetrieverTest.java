package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import com.agentcli.store.SqliteStore;
import com.agentcli.tool.search.CodeSearchEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CodeRetriever} 混合检索与降级决策树测试。
 *
 * <p>用手工构造的定长向量（而非真实 embedding）喂进 {@link VectorStore}，
 * 让余弦相似度完全可预测，从而把「混合加权排序」这件事变成可断言的确定性行为。
 *
 * <p>降级分支逐条覆盖：无 key / 无索引 / 请求失败 / 维度不符，
 * 全部要求「不外抛异常 + 走 Ripgrep 兜底 + 给出正确的 {@code DegradeReason}」。
 */
class CodeRetrieverTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private String projectKey;
    private VectorStore vectorStore;

    @BeforeEach
    void setUp() throws SQLException {
        projectRoot = tempDir.resolve("project");
        projectKey = VectorStore.projectKey(projectRoot);
        vectorStore = new VectorStore(SqliteStore.open(tempDir.resolve("retriever-test.db")));
    }

    // ---------------------------------------------------------------- 混合排序

    @Test
    @DisplayName("混合检索：语义与关键词双路命中的块排在首位")
    void dualHitChunkRanksFirst() throws SQLException {
        insert("src/Auth.java", CodeChunk.TYPE_METHOD, "AuthService.login",
                "public boolean login(String user) { return true; }", 10, 20, vec(1, 0, 0));
        insert("src/Payment.java", CodeChunk.TYPE_METHOD, "PaymentService.charge",
                "public void charge(int amount) { }", 30, 40, vec(1, 0, 0));
        insert("src/Helper.java", CodeChunk.TYPE_METHOD, "HelperKit.assist",
                "// helper for login flow", 50, 60, vec(0, 0, 1));

        CodeRetriever retriever = new CodeRetriever(vectorStore, QueryEmbedding.of(1, 0, 0), null);
        CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, "login", 5);

        assertEquals(CodeRetriever.Mode.SEMANTIC_HYBRID, outcome.mode());
        assertEquals(CodeRetriever.DegradeReason.NONE, outcome.degradeReason());
        assertFalse(outcome.degraded());

        List<VectorStore.SearchResult> results = outcome.results();
        assertEquals(3, results.size());
        assertEquals("AuthService.login", results.get(0).name(),
                "语义满分 + 块名/内容关键词命中，应排第一");
        assertEquals("PaymentService.charge", results.get(1).name(),
                "语义满分但关键词未命中，应排第二");
        assertEquals("HelperKit.assist", results.get(2).name(),
                "仅关键词命中、语义为 0，应排最后");
        assertTrue(results.get(0).score() > results.get(1).score(),
                "双路命中必须拿到更高分: " + results.get(0).score() + " vs " + results.get(1).score());
    }

    @Test
    @DisplayName("块类型加分生效：语义同分时 method 优于 class 优于 file")
    void methodChunkOutranksTypeAndFileChunk() throws SQLException {
        insert("src/Aaa.java", CodeChunk.TYPE_METHOD, "Aaa.doWork", "int a = 1;", 1, 5, vec(1, 0, 0));
        insert("src/Bbb.java", "class", "Bbb", "int b = 2;", 1, 5, vec(1, 0, 0));
        insert("src/Ccc.java", CodeChunk.TYPE_FILE, "src/Ccc.java#1", "int c = 3;", 1, 5, vec(1, 0, 0));

        CodeRetriever retriever = new CodeRetriever(vectorStore, QueryEmbedding.of(1, 0, 0), null);
        // 查询词刻意与任何块名 / 路径 / 内容都不相交，隔离出纯粹的类型加分效果
        CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, "qqq", 5);

        List<VectorStore.SearchResult> results = outcome.results();
        assertEquals(3, results.size());
        assertEquals("Aaa.doWork", results.get(0).name());
        assertEquals("Bbb", results.get(1).name());
        assertEquals("src/Ccc.java#1", results.get(2).name());
    }

    @Test
    @DisplayName("同一文件最多返回 2 条，避免单个大文件霸屏")
    void atMostTwoResultsPerFile() throws SQLException {
        for (int i = 1; i <= 4; i++) {
            insert("src/Big.java", CodeChunk.TYPE_FILE, "src/Big.java#" + i,
                    "segment " + i, i * 10, i * 10 + 5, vec(1, 0, 0));
        }
        insert("src/Small.java", CodeChunk.TYPE_FILE, "src/Small.java#1",
                "tiny", 1, 5, vec(1, 0, 0));

        CodeRetriever retriever = new CodeRetriever(vectorStore, QueryEmbedding.of(1, 0, 0), null);
        CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, "qqq", 5);

        List<VectorStore.SearchResult> results = outcome.results();
        long fromBig = results.stream().filter(r -> "src/Big.java".equals(r.filePath())).count();
        long fromSmall = results.stream().filter(r -> "src/Small.java".equals(r.filePath())).count();
        assertEquals(2, fromBig, "同一文件最多 2 条");
        assertEquals(1, fromSmall);
        assertEquals(3, results.size());
    }

    @Test
    @DisplayName("topK 生效：请求 1 条只返回 1 条")
    void topKIsRespected() throws SQLException {
        insert("src/Aaa.java", CodeChunk.TYPE_METHOD, "Aaa.one", "one", 1, 5, vec(1, 0, 0));
        insert("src/Bbb.java", CodeChunk.TYPE_METHOD, "Bbb.two", "two", 1, 5, vec(1, 0, 0));

        CodeRetriever retriever = new CodeRetriever(vectorStore, QueryEmbedding.of(1, 0, 0), null);

        assertEquals(1, retriever.search(projectKey, projectRoot, "qqq", 1).results().size());
        assertEquals(2, retriever.search(projectKey, projectRoot, "qqq", 0).results().size(),
                "topK <= 0 时退回默认值 " + CodeRetriever.DEFAULT_TOP_K);
    }

    // ---------------------------------------------------------------- 降级决策树

    @Test
    @DisplayName("未配置 embedding：降级为 KEYWORD_FALLBACK，原因 EMBEDDING_NOT_CONFIGURED")
    void withoutEmbeddingFallsBackToKeyword() {
        StubSearchEngine engine = StubSearchEngine.returning(
                match(projectRoot.resolve("src/Auth.java").toString(), 12, "public void login() {"));
        CodeRetriever retriever = new CodeRetriever(
                vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"), engine);

        CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, "login", 5);

        assertEquals(CodeRetriever.Mode.KEYWORD_FALLBACK, outcome.mode());
        assertEquals(CodeRetriever.DegradeReason.EMBEDDING_NOT_CONFIGURED, outcome.degradeReason());
        assertTrue(outcome.degraded());
        assertTrue(outcome.degradeMessage().startsWith("⚠️"), "实际: " + outcome.degradeMessage());
        assertTrue(outcome.degradeMessage().contains("未配置 embedding"));
        assertEquals(1, engine.callCount(), "必须真的跑一遍 Ripgrep 兜底");

        assertEquals(1, outcome.results().size());
        VectorStore.SearchResult hit = outcome.results().get(0);
        assertEquals("src/Auth.java", hit.filePath(), "兜底结果需相对化到项目根");
        assertEquals(12, hit.startLine());
        assertTrue(hit.content().contains("public void login() {"));
    }

    @Test
    @DisplayName("索引为空：降级并给出 INDEX_MISSING")
    void emptyIndexDegradesWithIndexMissing() {
        CodeRetriever retriever = new CodeRetriever(
                vectorStore, QueryEmbedding.of(1, 0, 0), StubSearchEngine.returning());

        CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, "login", 5);

        assertEquals(CodeRetriever.Mode.KEYWORD_FALLBACK, outcome.mode());
        assertEquals(CodeRetriever.DegradeReason.INDEX_MISSING, outcome.degradeReason());
        assertTrue(outcome.degradeMessage().contains("/index"), "应引导用户先建索引");
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    @DisplayName("查询向量化抛异常：返回 EMBEDDING_REQUEST_FAILED 且不外抛")
    void embedFailureIsAbsorbed() throws SQLException {
        insert("src/Auth.java", CodeChunk.TYPE_METHOD, "AuthService.login", "login()", 1, 5, vec(1, 0, 0));
        CodeRetriever retriever = new CodeRetriever(
                vectorStore, QueryEmbedding.failing("connect timeout"), StubSearchEngine.returning());

        CodeRetriever.SearchOutcome outcome = assertDoesNotThrow(
                () -> retriever.search(projectKey, projectRoot, "login", 5));

        assertEquals(CodeRetriever.Mode.KEYWORD_FALLBACK, outcome.mode());
        assertEquals(CodeRetriever.DegradeReason.EMBEDDING_REQUEST_FAILED, outcome.degradeReason());
        assertEquals("connect timeout", outcome.detail());
        assertTrue(outcome.degradeMessage().contains("connect timeout"), "文案应带上失败原因摘要");
    }

    @Test
    @DisplayName("查询向量维度与索引不符：返回 INDEX_DIMENSION_MISMATCH 并提示重建")
    void dimensionMismatchIsDetected() throws SQLException {
        insert("src/Auth.java", CodeChunk.TYPE_METHOD, "AuthService.login", "login()", 1, 5, vec(1, 0, 0));
        CodeRetriever retriever = new CodeRetriever(
                vectorStore, QueryEmbedding.of(1, 0, 0, 0), StubSearchEngine.returning());

        CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, "login", 5);

        assertEquals(CodeRetriever.DegradeReason.INDEX_DIMENSION_MISMATCH, outcome.degradeReason());
        assertEquals("stub-model", outcome.detail(), "细节位应带出建索引时的模型名");
        assertTrue(outcome.degradeMessage().contains("stub-model"));
        assertTrue(outcome.degradeMessage().contains("重新 /index"));
    }

    @Test
    @DisplayName("空查询直接返回空语义结果，不触发降级也不打扰 embedding")
    void blankQueryReturnsEmptySemanticOutcome() {
        StubSearchEngine engine = StubSearchEngine.returning();
        CodeRetriever retriever = new CodeRetriever(
                vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"), engine);

        for (String query : new String[]{null, "", "   "}) {
            CodeRetriever.SearchOutcome outcome = retriever.search(projectKey, projectRoot, query, 5);
            assertFalse(outcome.degraded(), "空查询不应报降级");
            assertTrue(outcome.results().isEmpty());
        }
        assertEquals(0, engine.callCount(), "空查询不该触发兜底检索");
    }

    @Test
    @DisplayName("兜底引擎缺失或自身抛异常时静默返回空结果")
    void brokenFallbackEngineIsAbsorbed() {
        CodeRetriever noEngine = new CodeRetriever(
                vectorStore, EmbeddingClient.disabled("未配置"), null);
        CodeRetriever.SearchOutcome first = assertDoesNotThrow(
                () -> noEngine.search(projectKey, projectRoot, "login", 5));
        assertTrue(first.degraded());
        assertTrue(first.results().isEmpty());

        CodeRetriever throwingEngine = new CodeRetriever(
                vectorStore, EmbeddingClient.disabled("未配置"), StubSearchEngine.throwing());
        CodeRetriever.SearchOutcome second = assertDoesNotThrow(
                () -> throwingEngine.search(projectKey, projectRoot, "login", 5));
        assertTrue(second.degraded());
        assertTrue(second.results().isEmpty(), "降级路径自身失败也不得抛到用户面前");
    }

    // ---------------------------------------------------------------- 关系通路

    @Test
    @DisplayName("relations 完全不经过 embedding：无 key 时仍返回完整关系")
    void relationsWorkWithoutEmbedding() throws SQLException {
        vectorStore.insertRelations(projectKey, List.of(
                new CodeRelation("src/Alpha.java", "Alpha", null, "BaseAlpha", CodeRelation.EXTENDS),
                new CodeRelation("src/Alpha.java", "Alpha", "src/Alpha.java", "Alpha.run", CodeRelation.CONTAINS),
                new CodeRelation("src/Beta.java", "Beta.call", null, "Alpha.run", CodeRelation.CALLS)));

        CodeRetriever retriever = new CodeRetriever(
                vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"), null);

        List<CodeRelation> relations = retriever.relations(projectKey, "Alpha");
        assertEquals(2, relations.size(), "Alpha 的出向 extends + contains");
        assertTrue(relations.stream().anyMatch(r -> CodeRelation.EXTENDS.equals(r.relationType())));
        assertTrue(relations.stream().anyMatch(r -> CodeRelation.CONTAINS.equals(r.relationType())));

        assertEquals(2, retriever.relations(projectKey, "  Alpha  ").size(), "查询名应先 trim");
        assertTrue(retriever.relations(projectKey, null).isEmpty());
        assertTrue(retriever.relations(projectKey, "   ").isEmpty());
        assertTrue(retriever.relations(projectKey, "NotExists").isEmpty());
    }

    @Test
    @DisplayName("stats 读取失败时返回空统计而非抛异常")
    void statsNeverThrows() throws SQLException {
        insert("src/Auth.java", CodeChunk.TYPE_METHOD, "AuthService.login", "login()", 1, 5, vec(1, 0, 0));
        CodeRetriever retriever = new CodeRetriever(vectorStore, QueryEmbedding.of(1, 0, 0), null);

        assertEquals(1, retriever.stats(projectKey).chunkCount());
        assertEquals(0, retriever.stats("不存在的项目").chunkCount());
        assertDoesNotThrow(() -> retriever.stats(null));
    }

    // ---------------------------------------------------------------- 测试夹具

    private void insert(String filePath, String chunkType, String name, String content,
                        int startLine, int endLine, float[] vector) throws SQLException {
        vectorStore.insertChunks(projectKey, List.of(new VectorStore.ChunkEntry(
                new CodeChunk(filePath, chunkType, name, content, startLine, endLine),
                vector, "stub-model")));
    }

    private static float[] vec(float... values) {
        return values;
    }

    private static CodeSearchEngine.GrepMatch match(String file, int lineNumber, String text) {
        return new CodeSearchEngine.GrepMatch(file, lineNumber,
                List.of(new CodeSearchEngine.ContextLine(lineNumber, text)));
    }

    /** 固定返回预设查询向量的 embedding 桩，可切换为「必定失败」。 */
    private static final class QueryEmbedding implements EmbeddingClient {

        private final float[] vector;
        private final IOException failure;

        private QueryEmbedding(float[] vector, IOException failure) {
            this.vector = vector;
            this.failure = failure;
        }

        static QueryEmbedding of(float... vector) {
            return new QueryEmbedding(vector, null);
        }

        static QueryEmbedding failing(String reason) {
            return new QueryEmbedding(null, new IOException(reason));
        }

        @Override
        public float[] embed(String text) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return vector.clone();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return null;
        }

        @Override
        public String modelName() {
            return "stub-model";
        }

        @Override
        public String baseUrl() {
            return "http://stub/v1";
        }
    }

    /** 可计数的降级检索引擎桩。 */
    private static final class StubSearchEngine implements CodeSearchEngine {

        private final List<GrepMatch> matches;
        private final boolean throwing;
        private int callCount;

        private StubSearchEngine(List<GrepMatch> matches, boolean throwing) {
            this.matches = matches;
            this.throwing = throwing;
        }

        static StubSearchEngine returning(GrepMatch... matches) {
            return new StubSearchEngine(List.of(matches), false);
        }

        static StubSearchEngine throwing() {
            return new StubSearchEngine(List.of(), true);
        }

        int callCount() {
            return callCount;
        }

        @Override
        public CodeSearchResult search(CodeSearchRequest request) {
            callCount++;
            if (throwing) {
                throw new IllegalStateException("stub 搜索引擎故意失败");
            }
            return new CodeSearchResult("stub", matches, false, null);
        }
    }
}
