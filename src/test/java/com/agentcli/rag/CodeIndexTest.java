package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.agentcli.store.SqliteStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CodeIndex} 集成测试：临时项目 + 临时 SQLite 库 + stub embedding。
 *
 * <p>重点固化主理人决策：<b>无 embedding 时只建结构索引</b>，{@code code_chunks} 为空，
 * 但关系图谱完整可用。
 */
class CodeIndexTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private SqliteStore sqliteStore;
    private VectorStore vectorStore;

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

        sqliteStore = SqliteStore.open(tempDir.resolve("rag-test.db"));
        vectorStore = new VectorStore(sqliteStore);
    }

    private VectorStore.IndexStats stats() throws SQLException {
        return vectorStore.stats(VectorStore.projectKey(projectRoot));
    }

    @Test
    @DisplayName("路径不存在时返回失败结果且不抛异常")
    void missingPathReturnsFailure() {
        CodeIndex index = new CodeIndex(vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"));

        CodeIndex.IndexResult result = index.index(tempDir.resolve("not-exists"), CodeIndex.ProgressListener.noop());

        assertFalse(result.success());
        assertTrue(result.message().contains("路径不存在"), "实际: " + result.message());
        assertEquals(0, result.chunkCount());

        CodeIndex.IndexResult nullPath = index.index(null, null);
        assertFalse(nullPath.success());
    }

    @Test
    @DisplayName("无 embedding 时只建结构索引：chunkCount 为 0、relationCount 大于 0、vectorized 为 false")
    void withoutEmbeddingOnlyStructuralIndexIsBuilt() throws SQLException {
        CodeIndex index = new CodeIndex(vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"));
        List<String> messages = new ArrayList<>();

        CodeIndex.IndexResult result = index.index(projectRoot, messages::add);

        assertTrue(result.success());
        assertFalse(result.vectorized(), "无 key 时不得声称已向量化");
        assertEquals(0, result.chunkCount(), "无 key 时不得写入任何向量块");
        assertTrue(result.relationCount() > 0, "关系图谱不依赖 embedding");
        assertEquals(2, result.fileCount());
        assertEquals("未配置 embedding.apiKey", result.degradeReason());

        VectorStore.IndexStats stats = stats();
        assertEquals(0, stats.chunkCount());
        assertTrue(stats.hasRelations());
        assertTrue(messages.stream().anyMatch(m -> m.contains("跳过向量化，仅建立结构索引")),
                "应给出降级提示，实际: " + messages);
    }

    @Test
    @DisplayName("有 embedding 时 chunk 与 relation 均入库")
    void withEmbeddingBothTablesAreWritten() throws SQLException {
        StubEmbeddingClient embedding = StubEmbeddingClient.available(3);
        CodeIndex index = new CodeIndex(vectorStore, embedding);

        CodeIndex.IndexResult result = index.index(projectRoot, CodeIndex.ProgressListener.noop());

        assertTrue(result.success());
        assertTrue(result.vectorized());
        assertTrue(result.chunkCount() > 0);
        assertTrue(result.relationCount() > 0);

        VectorStore.IndexStats stats = stats();
        assertEquals(result.chunkCount(), stats.chunkCount());
        assertEquals(3, stats.embeddingDim());
        assertEquals("stub-model", stats.embeddingModel());
    }

    @Test
    @DisplayName("重复索引幂等：条数不翻倍")
    void reindexingIsIdempotent() throws SQLException {
        CodeIndex index = new CodeIndex(vectorStore, StubEmbeddingClient.available(3));

        CodeIndex.IndexResult first = index.index(projectRoot, CodeIndex.ProgressListener.noop());
        CodeIndex.IndexResult second = index.index(projectRoot, CodeIndex.ProgressListener.noop());

        assertEquals(first.chunkCount(), second.chunkCount());
        assertEquals(first.relationCount(), second.relationCount());
        VectorStore.IndexStats stats = stats();
        assertEquals(second.chunkCount(), stats.chunkCount());
        assertEquals(second.relationCount(), stats.relationCount());
    }

    @Test
    @DisplayName("embed 连续失败 3 次熔断，转结构索引且不再调用 embed")
    void consecutiveEmbedFailuresTripCircuitBreaker() throws SQLException {
        StubEmbeddingClient embedding = StubEmbeddingClient.alwaysFailing();
        CodeIndex index = new CodeIndex(vectorStore, embedding);
        List<String> messages = new ArrayList<>();

        CodeIndex.IndexResult result = index.index(projectRoot, messages::add);

        assertTrue(result.success());
        assertFalse(result.vectorized());
        assertEquals(CodeIndex.EMBED_FAILURE_THRESHOLD, embedding.callCount(),
                "熔断后不得再调用 embed");
        assertEquals(0, result.chunkCount());
        assertTrue(result.relationCount() > 0, "熔断后仍应完成结构索引");
        assertTrue(messages.stream().anyMatch(m -> m.contains("向量化连续失败")),
                "应给出熔断提示，实际: " + messages);
        assertEquals(0, stats().chunkCount());
    }

    @Test
    @DisplayName("单文件读取失败只累加 failedFileCount，整体仍然成功")
    void unreadableFileOnlyIncrementsFailureCount() throws IOException {
        Files.write(projectRoot.resolve("src/Broken.java"), new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA9});
        CodeIndex index = new CodeIndex(vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"));

        CodeIndex.IndexResult result = index.index(projectRoot, CodeIndex.ProgressListener.noop());

        assertTrue(result.success());
        assertEquals(3, result.fileCount(), "扫描阶段应看到 3 个文件");
        assertEquals(1, result.failedFileCount(), "1 个文件读取失败");
        assertTrue(result.relationCount() > 0, "其余文件照常入库");
    }

    @Test
    @DisplayName("进度回调依次发出开始、扫描、写入三类消息")
    void progressListenerReceivesLifecycleMessages() {
        CodeIndex index = new CodeIndex(vectorStore, StubEmbeddingClient.available(2));
        List<String> messages = new ArrayList<>();

        index.index(projectRoot, messages::add);

        assertTrue(messages.get(0).startsWith("📦 开始索引"), "首条应为开始消息，实际: " + messages.get(0));
        assertTrue(messages.stream().anyMatch(m -> m.contains("扫描文件")), "缺少扫描消息: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("向量化")), "缺少向量化消息: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("写入索引")), "缺少写入消息: " + messages);
    }

    @Test
    @DisplayName("源文件修改时间晚于索引时间即判定过期")
    void freshnessDetectsStaleIndex() throws IOException, SQLException {
        CodeIndex index = new CodeIndex(vectorStore, EmbeddingClient.disabled("未配置 embedding.apiKey"));
        index.index(projectRoot, CodeIndex.ProgressListener.noop());

        assertFalse(index.checkFreshness(projectRoot, stats()).stale(), "刚建完索引不应判定过期");

        Files.setLastModifiedTime(projectRoot.resolve("src/Alpha.java"),
                FileTime.fromMillis(System.currentTimeMillis() + 60_000L));
        CodeIndex.Freshness freshness = index.checkFreshness(projectRoot, stats());

        assertTrue(freshness.stale());
        assertTrue(freshness.hint().contains("重新执行 /index"), "实际: " + freshness.hint());
        assertFalse(index.checkFreshness(projectRoot, VectorStore.IndexStats.empty()).stale(),
                "没有索引时不应报过期");
        assertFalse(index.checkFreshness(null, stats()).stale());
    }

    /** 可控的 embedding 桩：能数调用次数、能稳定产出伪向量、能一直失败。 */
    private static final class StubEmbeddingClient implements EmbeddingClient {

        private final boolean available;
        private final int dimension;
        private final boolean failing;
        private int callCount;

        private StubEmbeddingClient(boolean available, int dimension, boolean failing) {
            this.available = available;
            this.dimension = dimension;
            this.failing = failing;
        }

        static StubEmbeddingClient available(int dimension) {
            return new StubEmbeddingClient(true, dimension, false);
        }

        static StubEmbeddingClient alwaysFailing() {
            return new StubEmbeddingClient(true, 3, true);
        }

        int callCount() {
            return callCount;
        }

        @Override
        public float[] embed(String text) throws IOException {
            callCount++;
            if (failing) {
                throw new IOException("stub 故意失败");
            }
            float[] vector = new float[dimension];
            int hash = text == null ? 0 : text.hashCode();
            for (int i = 0; i < dimension; i++) {
                vector[i] = ((hash >> i) & 0xFF) / 255.0f + 0.01f;
            }
            return vector;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String unavailableReason() {
            return available ? null : "stub 不可用";
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
}
