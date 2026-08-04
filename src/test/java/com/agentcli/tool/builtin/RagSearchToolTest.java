package com.agentcli.tool.builtin;

import com.agentcli.rag.CodeRetriever;
import com.agentcli.rag.EmbeddingClient;
import com.agentcli.rag.VectorStore;
import com.agentcli.store.SqliteStore;
import com.agentcli.tool.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义检索工具用例。
 *
 * <p>检索器一律用<b>重写 {@code search()} 的匿名子类</b>注入：真实的检索行为已由
 * {@code CodeRetrieverTest} 覆盖，这里只关心工具外壳的参数钳制、排版与降级透出。
 * 构造父类需要一个非空 {@code VectorStore}，用临时目录里的库满足即可——
 * 由于 {@code search()} 被整体重写，这个库<b>自始至终不会被读写</b>。
 */
class RagSearchToolTest {

    @TempDir
    Path tempDir;

    private ToolContext context;
    private VectorStore idleStore;

    /** 记录工具实际传给检索层的条数，用于验证钳制。 */
    private final int[] capturedTopK = {-1};

    @BeforeEach
    void setUp() throws Exception {
        context = new ToolContext(tempDir.toString());
        idleStore = new VectorStore(SqliteStore.open(tempDir.resolve("rag-tool-test.db")));
    }

    private static VectorStore.SearchResult sampleResult() {
        return new VectorStore.SearchResult(
                "src/main/java/demo/AuthService.java", "method", "login",
                "public void login(String user) { }", 42, 58, 0.91);
    }

    private RagSearchTool toolReturning(CodeRetriever.SearchOutcome outcome) {
        CodeRetriever stub = new CodeRetriever(idleStore, EmbeddingClient.disabled("测试桩"), null) {
            @Override
            public SearchOutcome search(String projectKey, Path projectRoot, String query, int topK) {
                capturedTopK[0] = topK;
                return outcome;
            }
        };
        return new RagSearchTool(context, stub);
    }

    @Test
    void blankQueryReturnsSingleLineError() {
        RagSearchTool tool = toolReturning(CodeRetriever.SearchOutcome.semantic(List.of()));

        assertEquals("❌ 检索失败: query 不能为空", tool.execute(Map.of()));
        assertEquals("❌ 检索失败: query 不能为空", tool.execute(Map.of("query", "  ")));
        assertEquals(-1, capturedTopK[0], "参数非法时不应触达检索层");
    }

    @Test
    void formatsSemanticResults() {
        RagSearchTool tool = toolReturning(CodeRetriever.SearchOutcome.semantic(List.of(sampleResult())));

        String output = tool.execute(Map.of("query", "用户登录是怎么实现的"));

        assertTrue(output.contains("🔍 检索: 用户登录是怎么实现的"), output);
        assertTrue(output.contains("找到 1 条结果"), output);
        assertTrue(output.contains("login"), output);
        assertTrue(output.contains("AuthService.java:42-58"), output);
        assertFalse(output.contains("⚠️"), output);
    }

    @Test
    void emptyResultsAreReportedExplicitly() {
        RagSearchTool tool = toolReturning(CodeRetriever.SearchOutcome.semantic(List.of()));

        assertTrue(tool.execute(Map.of("query", "不存在的东西")).contains("未找到相关代码"));
    }

    @Test
    void degradeMessageIsExposed() {
        RagSearchTool tool = toolReturning(new CodeRetriever.SearchOutcome(
                CodeRetriever.Mode.KEYWORD_FALLBACK, CodeRetriever.DegradeReason.INDEX_MISSING,
                null, List.of(sampleResult())));

        String output = tool.execute(Map.of("query", "登录"));

        assertTrue(output.contains("⚠️"), output);
        assertTrue(output.contains("尚未建立语义索引"), output);
        // 降级不等于失败：结果照常给出
        assertTrue(output.contains("login"), output);
    }

    @Test
    void degradeMessageIsExposedOnEveryCall() {
        RagSearchTool tool = toolReturning(new CodeRetriever.SearchOutcome(
                CodeRetriever.Mode.KEYWORD_FALLBACK, CodeRetriever.DegradeReason.EMBEDDING_NOT_CONFIGURED,
                "未配置 embedding", List.of(sampleResult())));

        String first = tool.execute(Map.of("query", "登录"));
        String second = tool.execute(Map.of("query", "登录"));

        // 与 /search 的「一次性提示」策略不同：模型逐轮独立读结果，抹掉提示会让它高估结果成色
        assertTrue(first.contains("⚠️"), first);
        assertTrue(second.contains("⚠️"), second);
        assertEquals(first, second);
    }

    @Test
    void clampsTopKIntoAllowedRange() {
        RagSearchTool tool = toolReturning(CodeRetriever.SearchOutcome.semantic(List.of()));

        tool.execute(Map.of("query", "q", "top_k", "999"));
        assertEquals(20, capturedTopK[0]);

        tool.execute(Map.of("query", "q", "top_k", "0"));
        assertEquals(1, capturedTopK[0]);

        tool.execute(Map.of("query", "q", "top_k", "abc"));
        assertEquals(5, capturedTopK[0], "非法数字应回落到默认值");

        tool.execute(Map.of("query", "q"));
        assertEquals(5, capturedTopK[0]);
    }

    @Test
    void assemblyFailureYieldsActionableHintWithoutThrowing() throws Exception {
        // 直接写入「已装配且失败」的状态，避免为了造一次失败去真的破坏本机索引库
        RagSearchTool tool = new RagSearchTool(context);
        Field failure = RagSearchTool.class.getDeclaredField("initFailure");
        failure.setAccessible(true);
        failure.set(tool, "索引库无法打开");

        String output = tool.execute(Map.of("query", "登录"));

        assertEquals("⚠️ 语义检索不可用: 索引库无法打开；请改用 grep_code 关键词检索", output);
    }

    @Test
    void schemaAndDescriptionGuideToolSelection() {
        RagSearchTool tool = new RagSearchTool(context);
        String schema = tool.parameters().toString();

        assertEquals("rag_search", tool.name());
        assertTrue(schema.contains("query"), schema);
        assertTrue(schema.contains("top_k"), schema);
        assertTrue(tool.description().contains("grep_code"), tool.description());
    }
}
