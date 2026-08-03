package com.agentcli.rag;

import com.agentcli.store.SqliteStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 代码块向量与代码关系的双表 DAO。
 *
 * <p>不自建连接池、不自开 {@code DriverManager}，一律走 {@link SqliteStore#connect()} 取短连接，
 * 复用 v0.9.0 已有的统一库路径与 {@code busy_timeout=5000}；建表由
 * {@link SqliteStore} 的 {@code ensureSchema()} 幂等承担，本类只负责读写。
 *
 * <p><b>向量通路与关系通路彻底解耦</b>：{@code code_chunks}（向量）与 {@code code_relations}（关系）
 * 互不引用。无 embedding 配置时 {@code code_chunks} 为空，但关系图谱依旧完整可用——
 * 这是 {@code /graph} 不受 embedding 影响的存储层保证。
 *
 * <p>所有查询都以 {@code projectKey} 做项目隔离，同一个库可以并存多个项目的索引。
 */
public final class VectorStore {

    private static final String INSERT_CHUNK = """
            INSERT INTO code_chunks
                (project_path, file_path, chunk_type, name, content,
                 start_line, end_line, embedding_json, embedding_dim, embedding_model, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // 唯一索引 uk_code_relations 兜底去重，重复抽取到的同一条关系静默忽略
    private static final String INSERT_RELATION = """
            INSERT OR IGNORE INTO code_relations
                (project_path, from_file, from_name, to_file, to_name, relation_type, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final SqliteStore store;

    public VectorStore(SqliteStore store) {
        if (store == null) {
            throw new IllegalArgumentException("SqliteStore 不能为空");
        }
        this.store = store;
    }

    /** 项目隔离键：绝对化 + 规范化后的路径字符串。全模块唯一入口，保证读写用同一把钥匙。 */
    public static String projectKey(Path root) {
        if (root == null) {
            return "";
        }
        return root.toAbsolutePath().normalize().toString();
    }

    /** 清空指定项目的双表数据。 */
    public void clearProject(String projectKey) throws SQLException {
        try (Connection conn = store.connect()) {
            deleteProject(conn, projectKey);
        }
    }

    /**
     * 批量写入代码块（单事务）。
     *
     * @return 实际写入条数
     */
    public int insertChunks(String projectKey, List<ChunkEntry> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        try (Connection conn = store.connect()) {
            return inTransaction(conn, () -> writeChunks(conn, projectKey, entries));
        }
    }

    /**
     * 批量写入代码关系（单事务，{@code INSERT OR IGNORE} 去重）。
     *
     * @return 尝试写入条数（被唯一索引忽略的不计入）
     */
    public int insertRelations(String projectKey, List<CodeRelation> relations) throws SQLException {
        if (relations == null || relations.isEmpty()) {
            return 0;
        }
        try (Connection conn = store.connect()) {
            return inTransaction(conn, () -> writeRelations(conn, projectKey, relations));
        }
    }

    /**
     * 原子重建：清空 + 写块 + 写关系全部在同一事务内完成。
     *
     * <p>{@code /index} 专用。分成三次独立事务的话，一旦中途失败就会留下「旧索引已删、新索引没写完」
     * 的半损坏状态；合并成单事务后，失败即整体回滚，旧索引原样保留。
     *
     * @return 写入结果（块数 / 关系数）
     */
    public WriteResult replaceProject(String projectKey, List<ChunkEntry> entries,
                                      List<CodeRelation> relations) throws SQLException {
        try (Connection conn = store.connect()) {
            return inTransaction(conn, () -> {
                deleteProject(conn, projectKey);
                int chunks = writeChunks(conn, projectKey, entries);
                int rels = writeRelations(conn, projectKey, relations);
                return new WriteResult(chunks, rels);
            });
        }
    }

    /**
     * 语义检索：读取该项目全部带向量的行，在内存计算余弦相似度并取 topK。
     *
     * <p>只对 {@code embedding_dim == query.length} 的行计分——换了 embedding 模型后，
     * 1536 维索引遇上 768 维查询会静默算出一堆 0 分，把维度过滤前置能让上层准确识别
     * {@code INDEX_DIMENSION_MISMATCH} 而不是给用户一堆垃圾结果。
     */
    public List<SearchResult> searchByVector(String projectKey, float[] query, int topK) throws SQLException {
        List<SearchResult> candidates = new ArrayList<>();
        if (query == null || query.length == 0 || topK <= 0) {
            return candidates;
        }
        String sql = """
                SELECT file_path, chunk_type, name, content, start_line, end_line, embedding_json
                FROM code_chunks
                WHERE project_path = ? AND embedding_json IS NOT NULL AND embedding_dim = ?
                """;
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectKey);
            ps.setInt(2, query.length);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] embedding = VectorMath.fromJson(rs.getString("embedding_json"));
                    if (embedding.length != query.length) {
                        continue;
                    }
                    candidates.add(new SearchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            rs.getInt("start_line"),
                            rs.getInt("end_line"),
                            VectorMath.cosine(query, embedding)));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return candidates.size() > topK ? new ArrayList<>(candidates.subList(0, topK)) : candidates;
    }

    /**
     * 关键词检索：在 {@code name} 与 {@code content} 上做 LIKE 匹配，不经过 embedding。
     *
     * <p>关键词里的 {@code %} {@code _} {@code \} 会被转义，避免用户输入 {@code get_user}
     * 时下划线被当成 LIKE 通配符而召回一堆 {@code getXuser}。基准分固定 {@code 0.30}，
     * 由 {@link CodeRetriever} 再叠加命中位置加分。
     */
    public List<SearchResult> searchByKeyword(String projectKey, String keyword, int limit) throws SQLException {
        List<SearchResult> results = new ArrayList<>();
        if (keyword == null || keyword.isBlank() || limit <= 0) {
            return results;
        }
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        String sql = """
                SELECT file_path, chunk_type, name, content, start_line, end_line
                FROM code_chunks
                WHERE project_path = ?
                  AND (name LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')
                LIMIT ?
                """;
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectKey);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            rs.getInt("start_line"),
                            rs.getInt("end_line"),
                            KEYWORD_BASE_SCORE));
                }
            }
        }
        return results;
    }

    /** 关键词候选的基准分（语义候选是余弦相似度，关键词候选没有相似度，给一个保守底分）。 */
    public static final double KEYWORD_BASE_SCORE = 0.30;

    /** 图谱检索：同时返回以 {@code name} 为起点（出向）和终点（入向）的关系。 */
    public List<CodeRelation> findRelations(String projectKey, String name) throws SQLException {
        List<CodeRelation> results = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return results;
        }
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type
                FROM code_relations
                WHERE project_path = ? AND (from_name = ? OR to_name = ?)
                """;
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectKey);
            ps.setString(2, name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CodeRelation(
                            rs.getString("from_file"),
                            rs.getString("from_name"),
                            rs.getString("to_file"),
                            rs.getString("to_name"),
                            rs.getString("relation_type")));
                }
            }
        }
        return results;
    }

    /** 索引统计：块数 / 关系数 / 文件数 / 最近索引时间 / 向量模型与维度。 */
    public IndexStats stats(String projectKey) throws SQLException {
        int chunkCount = 0;
        int relationCount = 0;
        int fileCount = 0;
        long indexedAt = 0L;
        String model = null;
        int dim = 0;

        try (Connection conn = store.connect()) {
            chunkCount = countOf(conn, "SELECT COUNT(*) FROM code_chunks WHERE project_path = ?", projectKey);
            relationCount = countOf(conn, "SELECT COUNT(*) FROM code_relations WHERE project_path = ?", projectKey);

            // 文件数取双表并集：无 embedding 时 code_chunks 为空，仍应报出关系覆盖的文件数
            String fileSql = """
                    SELECT COUNT(*) FROM (
                        SELECT file_path AS f FROM code_chunks WHERE project_path = ?
                        UNION
                        SELECT from_file AS f FROM code_relations WHERE project_path = ?
                    )
                    """;
            try (PreparedStatement ps = conn.prepareStatement(fileSql)) {
                ps.setString(1, projectKey);
                ps.setString(2, projectKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        fileCount = rs.getInt(1);
                    }
                }
            }

            String tsSql = """
                    SELECT MAX(ts) FROM (
                        SELECT MAX(created_at) AS ts FROM code_chunks WHERE project_path = ?
                        UNION ALL
                        SELECT MAX(created_at) AS ts FROM code_relations WHERE project_path = ?
                    )
                    """;
            try (PreparedStatement ps = conn.prepareStatement(tsSql)) {
                ps.setString(1, projectKey);
                ps.setString(2, projectKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        indexedAt = rs.getLong(1);
                    }
                }
            }

            // 一次 /index 内必然同模型同维度，取任一行即可代表整个索引
            String modelSql = """
                    SELECT embedding_model, embedding_dim FROM code_chunks
                    WHERE project_path = ? AND embedding_dim > 0 LIMIT 1
                    """;
            try (PreparedStatement ps = conn.prepareStatement(modelSql)) {
                ps.setString(1, projectKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        model = rs.getString("embedding_model");
                        dim = rs.getInt("embedding_dim");
                    }
                }
            }
        }
        return new IndexStats(chunkCount, relationCount, fileCount, indexedAt, model, dim);
    }

    // ---------------------------------------------------------------- 内部实现

    private static void deleteProject(Connection conn, String projectKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM code_chunks WHERE project_path = ?")) {
            ps.setString(1, projectKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM code_relations WHERE project_path = ?")) {
            ps.setString(1, projectKey);
            ps.executeUpdate();
        }
    }

    private static int writeChunks(Connection conn, String projectKey, List<ChunkEntry> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int written = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_CHUNK)) {
            for (ChunkEntry entry : entries) {
                if (entry == null || entry.chunk() == null
                        || entry.embedding() == null || entry.embedding().length == 0) {
                    // 无向量不入库：让 chunkCount() 恒等于「可用于语义检索的块数」，判定口径唯一
                    continue;
                }
                CodeChunk chunk = entry.chunk();
                ps.setString(1, projectKey);
                ps.setString(2, chunk.filePath());
                ps.setString(3, chunk.chunkType());
                ps.setString(4, chunk.name());
                ps.setString(5, chunk.content());
                ps.setInt(6, chunk.startLine());
                ps.setInt(7, chunk.endLine());
                ps.setString(8, VectorMath.toJson(entry.embedding()));
                ps.setInt(9, entry.embedding().length);
                ps.setString(10, entry.model());
                ps.setLong(11, now);
                ps.addBatch();
                written++;
            }
            ps.executeBatch();
        }
        return written;
    }

    private static int writeRelations(Connection conn, String projectKey,
                                      List<CodeRelation> relations) throws SQLException {
        if (relations == null || relations.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int written = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_RELATION)) {
            for (CodeRelation relation : relations) {
                if (relation == null || relation.fromName() == null || relation.toName() == null) {
                    continue;
                }
                ps.setString(1, projectKey);
                ps.setString(2, relation.fromFile());
                ps.setString(3, relation.fromName());
                ps.setString(4, relation.toFile());
                ps.setString(5, relation.toName());
                ps.setString(6, relation.relationType());
                ps.setLong(7, now);
                ps.addBatch();
                written++;
            }
            ps.executeBatch();
        }
        return written;
    }

    private static int countOf(Connection conn, String sql, String projectKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 在单事务内执行一段写操作，异常回滚并恢复 autoCommit。 */
    private static <T> T inTransaction(Connection conn, SqlAction<T> action) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T result = action.run();
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    // ---------------------------------------------------------------- 嵌套类型

    /** 待入库的代码块条目：块本体 + 向量 + 产出该向量的模型名。 */
    public record ChunkEntry(CodeChunk chunk, float[] embedding, String model) {
    }

    /** 单次写入结果。 */
    public record WriteResult(int chunkCount, int relationCount) {
    }

    /** 检索结果：块的定位信息 + 混合评分。 */
    public record SearchResult(
            String filePath,
            String chunkType,
            String name,
            String content,
            int startLine,
            int endLine,
            double score) {

        /** 返回一个只替换分数的副本（record 不可变，混合评分靠复制推进）。 */
        public SearchResult withScore(double score) {
            return new SearchResult(filePath, chunkType, name, content, startLine, endLine, score);
        }

        /** 去重键：同一文件同一块名同一起始行视为同一条结果。 */
        public String key() {
            return filePath + "#" + name + "#" + startLine;
        }
    }

    /** 索引统计快照。 */
    public record IndexStats(
            int chunkCount,
            int relationCount,
            int fileCount,
            long indexedAt,
            String embeddingModel,
            int embeddingDim) {

        /** 是否存在可用于语义检索的向量块。 */
        public boolean hasVectors() {
            return chunkCount > 0;
        }

        /** 是否存在可用于 {@code /graph} 的关系数据（与向量完全无关）。 */
        public boolean hasRelations() {
            return relationCount > 0;
        }

        /** 空索引常量，供读取失败时降级返回。 */
        public static IndexStats empty() {
            return new IndexStats(0, 0, 0, 0L, null, 0);
        }
    }
}
