package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.agentcli.store.SqliteStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RagCommandHandler} 门面测试。
 *
 * <p>全程使用 <b>禁用态 embedding</b>（模拟「用户没配 key」这一最常见现实场景），
 * 借此把主理人决策 4 钉死：<b>{@code /graph} 不依赖 embedding</b>——
 * 向量表为空时图谱依旧必须完整可用。
 */
class RagCommandHandlerTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private VectorStore vectorStore;
    private RagCommandHandler handler;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/Alpha.java"), """
                package demo;

                public class Alpha extends BaseAlpha implements Runner {

                    private final Beta beta = new Beta();

                    public void run() {
                        beta.ping();
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("src/Beta.java"), """
                package demo;

                public class Beta {

                    public void ping() {
                        System.out.println("pong");
                    }
                }
                """, StandardCharsets.UTF_8);

        vectorStore = new VectorStore(SqliteStore.open(tempDir.resolve("handler-test.db")));
        handler = newHandler();
    }

    /** 无 embedding key、无 Ripgrep 兜底的门面——最纯粹的降级形态。 */
    private RagCommandHandler newHandler() {
        EmbeddingClient off = EmbeddingClient.disabled("未配置 embedding.apiKey");
        return new RagCommandHandler(
                new CodeIndex(vectorStore, off),
                new CodeRetriever(vectorStore, off, null),
                projectRoot);
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    @DisplayName("空参数：/search 与 /graph 给出用法提示，/index 缺省索引会话项目根")
    void blankPayloadsAreHandled() {
        for (String blank : new String[]{null, "", "   "}) {
            assertEquals("❌ 请提供检索关键词，例如 /search 用户登录实现", handler.handleSearch(blank));
            assertEquals("❌ 请提供类名，例如 /graph ReactAgent", handler.handleGraph(blank));
        }

        String indexed = handler.handleIndex(null, CodeIndex.ProgressListener.noop());
        assertTrue(indexed.startsWith("✅"), "/index 无参应索引会话项目根，实际: " + indexed);
    }

    @Test
    @DisplayName("依赖为 null 时构造失败，不容忍半残实例")
    void constructorRejectsNullDependencies() {
        EmbeddingClient off = EmbeddingClient.disabled("未配置");
        CodeIndex index = new CodeIndex(vectorStore, off);
        CodeRetriever retriever = new CodeRetriever(vectorStore, off, null);

        assertThrows(IllegalArgumentException.class,
                () -> new RagCommandHandler(null, retriever, projectRoot));
        assertThrows(IllegalArgumentException.class,
                () -> new RagCommandHandler(index, null, projectRoot));
        // 项目根允许为 null，退化为当前工作目录
        assertNotNull(new RagCommandHandler(index, retriever, null));
        assertNotNull(RagCommandHandler.lastCreateFailure(), "失败原因快照不得为 null");
    }

    // ---------------------------------------------------------------- /index

    @Test
    @DisplayName("/index 路径不存在时只输出一行 ❌ 错误")
    void missingPathYieldsSingleLineError() {
        String text = handler.handleIndex("no-such-dir", CodeIndex.ProgressListener.noop());

        assertEquals(1, text.lines().count(), "用户界面永远只出一行，实际: " + text);
        assertTrue(text.startsWith("❌ 索引失败: 路径不存在"), "实际: " + text);
    }

    @Test
    @DisplayName("/index 无 embedding 时报告结构索引完成，不谎称已向量化")
    void structuralIndexReportsHonestSummary() {
        List<String> progress = new ArrayList<>();

        String text = handler.handleIndex(null, progress::add);

        assertTrue(text.startsWith("✅ 结构索引完成："), "实际: " + text);
        assertTrue(text.contains("2 文件"), "实际: " + text);
        assertTrue(text.contains("条关系"), "实际: " + text);
        assertTrue(text.contains("耗时 "), "实际: " + text);
        assertFalse(text.contains(" 块 "), "无向量时不应出现块数，实际: " + text);
        assertTrue(progress.stream().anyMatch(m -> m.startsWith("📦 开始索引")),
                "进度回调应被转发，实际: " + progress);
    }

    @Test
    @DisplayName("/index 目标路径不是会话项目根时追加提示，且不改变会话作用域")
    void indexingForeignPathAppendsHint() throws IOException {
        Path other = tempDir.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("Gamma.java"),
                "public class Gamma extends BaseGamma { }", StandardCharsets.UTF_8);

        String text = handler.handleIndex(other.toString(), CodeIndex.ProgressListener.noop());

        assertTrue(text.contains("提示：本次索引的是"), "实际: " + text);
        assertTrue(text.contains("仍作用于当前会话项目"), "实际: " + text);
        // 会话作用域没有被污染：/graph 查 Gamma 依旧查不到（它写在另一个 project_path 下）
        assertTrue(handler.handleGraph("Gamma").contains("📭"),
                "跨路径索引不得影响会话项目的检索作用域");
    }

    // ---------------------------------------------------------------- /graph

    @Test
    @DisplayName("尚未建立索引时 /graph 输出 📭 空态并引导先 /index")
    void graphWithoutIndexShowsEmptyState() {
        String text = handler.handleGraph("Alpha");

        assertTrue(text.contains("📭"), "实际: " + text);
        assertTrue(text.contains("先执行 /index"), "实际: " + text);
    }

    @Test
    @DisplayName("无 embedding key 但有关系数据时 /graph 正常输出图谱（主理人决策 4）")
    void graphWorksWithoutEmbeddingKey() throws SQLException {
        handler.handleIndex(null, CodeIndex.ProgressListener.noop());

        VectorStore.IndexStats stats = vectorStore.stats(VectorStore.projectKey(projectRoot));
        assertEquals(0, stats.chunkCount(), "无 key 时向量表必须为空，前提才成立");
        assertTrue(stats.relationCount() > 0, "关系表必须有数据");

        String text = handler.handleGraph("Alpha");

        assertTrue(text.startsWith("🕸️ 查询类关系图谱: Alpha"), "实际: " + text);
        assertTrue(text.contains("📋 找到 "), "实际: " + text);
        assertTrue(text.contains("extends") && text.contains("BaseAlpha"), "实际: " + text);
        assertTrue(text.contains("implements") && text.contains("Runner"), "实际: " + text);
        assertTrue(text.contains("contains") && text.contains("run()"), "实际: " + text);
        assertFalse(text.contains("📭"), "有关系数据时不得走空态分支");
        assertFalse(text.contains("❌"), "不得因为缺 embedding 而报错");
    }

    @Test
    @DisplayName("类名拼错时 /graph 输出 📭 空态而非报错")
    void graphWithUnknownNameShowsEmptyState() {
        handler.handleIndex(null, CodeIndex.ProgressListener.noop());

        String text = handler.handleGraph("NotExistsClass");

        assertTrue(text.contains("📭"), "实际: " + text);
        assertTrue(text.contains("请确认类名拼写"), "实际: " + text);
    }

    // ---------------------------------------------------------------- /search

    @Test
    @DisplayName("降级提示一次性：连续两次 /search 只出现一次 ⚠️")
    void degradeNoticeIsShownOnlyOnce() {
        String first = handler.handleSearch("用户登录");
        String second = handler.handleSearch("订单支付");

        assertTrue(first.contains("⚠️"), "首次降级必须提示，实际: " + first);
        assertTrue(first.contains("未配置 embedding"), "实际: " + first);
        assertFalse(second.contains("⚠️"), "第二次不应重复刷警告，实际: " + second);
        assertTrue(second.startsWith("🔍 检索: 订单支付"), "结果本体照常输出，实际: " + second);
    }

    @Test
    @DisplayName("降级提示状态按门面实例隔离，新会话重新提示一次")
    void degradeNoticeResetsPerHandlerInstance() {
        handler.handleSearch("用户登录");
        assertFalse(handler.handleSearch("再来一次").contains("⚠️"));

        assertTrue(newHandler().handleSearch("用户登录").contains("⚠️"),
                "新门面实例代表新会话，应重新提示一次");
    }

    @Test
    @DisplayName("/search 无兜底结果时输出空态，且不外抛异常")
    void searchWithoutResultsShowsEmptyState() {
        String text = handler.handleSearch("完全不存在的关键词");

        assertTrue(text.startsWith("🔍 检索: 完全不存在的关键词"), "实际: " + text);
        assertTrue(text.contains("📭 未找到相关代码"), "实际: " + text);
    }

    @Test
    @DisplayName("查询串前后空白被裁剪后再展示")
    void searchQueryIsTrimmed() {
        String text = handler.handleSearch("   用户登录   ");

        assertTrue(text.startsWith("🔍 检索: 用户登录\n"), "实际首行: " + text.lines().findFirst().orElse(""));
    }
}
