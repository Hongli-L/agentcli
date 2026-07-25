package com.agentcli.memory;

import com.agentcli.common.AppConfig;
import com.agentcli.common.EnvLoader;
import com.agentcli.store.SqliteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆：跨会话持久化的关键事实与偏好。
 *
 * <p>v0.9.0 起落盘后端从 JSON 文件改为 SQLite（{@code ~/.agentcli/db/agentcli.db} 的
 * {@code memory_entries} 表），保持 {@code ConcurrentHashMap} 热路径不变——读仍走内存，
 * 写操作同步落库。首次启动若库为空且存在旧版 {@code long_term_memory.json} 会自动导入。
 */
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_FILE = "long_term_memory.json";

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final File storageFile;
    private final SqliteStore store;

    public LongTermMemory() {
        this(openDefaultStore(), resolveStorageDir());
    }

    private static SqliteStore openDefaultStore() {
        try {
            return SqliteStore.defaultStore();
        } catch (SQLException e) {
            throw new IllegalStateException("无法打开默认长期记忆数据库: " + e.getMessage(), e);
        }
    }

    public LongTermMemory(SqliteStore store) {
        this(store, resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this(openStore(storageDir), storageDir);
    }

    private LongTermMemory(SqliteStore store, File storageDir) {
        this.store = store;
        this.storageFile = new File(storageDir, STORAGE_FILE);
        loadFromStoreOrImport();
    }

    private static SqliteStore openStore(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        try {
            return SqliteStore.open(new File(dir, "agentcli.db").toPath());
        } catch (SQLException e) {
            throw new IllegalStateException("无法打开长期记忆数据库: " + e.getMessage(), e);
        }
    }

    public void store(MemoryEntry entry) {
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }
        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());
        persistEntry(entry);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)
                        || entry.getMetadata().values().stream()
                        .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens)))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            removeEntry(id);
            return true;
        }
        return false;
    }

    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        clearSqlite();
    }

    public int getTokenCount() {
        return tokenCounter.get();
    }

    public int size() {
        return entries.size();
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        return "project".equalsIgnoreCase(scope) ? "project" : "global";
    }

    public String getStatusSummary() {
        return String.format("长期记忆: %d 条 / %d tokens", entries.size(), tokenCounter.get());
    }

    // ---- 持久化：SQLite ----

    private void loadFromStoreOrImport() {
        try {
            if (loadFromSqlite() == 0 && storageFile.exists()) {
                importFromLegacyJson();
            }
        } catch (SQLException e) {
            log.warn("加载长期记忆(SQLite)失败，尝试 JSON 导入: {}", e.getMessage());
            importFromLegacyJson();
        }
    }

    private int loadFromSqlite() throws SQLException {
        int count = 0;
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, content, type, metadata_json, token_count, created_at FROM memory_entries")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf(rs.getString("type"));
                    Map<String, String> metadata = parseMetadata(rs.getString("metadata_json"));
                    int tokenCount = rs.getInt("token_count");
                    Instant ts = Instant.ofEpochMilli(rs.getLong("created_at"));
                    MemoryEntry entry = new MemoryEntry(id, content, type, ts, metadata, tokenCount);
                    entries.put(id, entry);
                    tokenCounter.addAndGet(tokenCount);
                    count++;
                }
            }
        }
        if (count > 0) {
            log.info("从 SQLite 加载了 {} 条长期记忆", count);
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void importFromLegacyJson() {
        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                    persistEntry(entry);
                }
            }
            log.info("已从旧版 JSON 导入 {} 条长期记忆到 SQLite", entries.size());
        } catch (IOException e) {
            log.warn("导入旧版 JSON 记忆失败: {}", e.getMessage());
        }
    }

    private void persistEntry(MemoryEntry entry) {
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO memory_entries(id, content, type, metadata_json, token_count, created_at) "
                             + "VALUES(?,?,?,?,?,?) "
                             + "ON CONFLICT(id) DO UPDATE SET content=excluded.content, type=excluded.type, "
                             + "metadata_json=excluded.metadata_json, token_count=excluded.token_count, "
                             + "created_at=excluded.created_at")) {
            ps.setString(1, entry.getId());
            ps.setString(2, entry.getContent());
            ps.setString(3, entry.getType().name());
            ps.setString(4, mapper.writeValueAsString(entry.getMetadata()));
            ps.setInt(5, entry.getTokenCount());
            ps.setLong(6, entry.getTimestamp().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage());
        }
    }

    private void removeEntry(String id) {
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM memory_entries WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除长期记忆失败: {}", e.getMessage());
        }
    }

    private void clearSqlite() {
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM memory_entries")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清空长期记忆失败: {}", e.getMessage());
        }
    }

    private Map<String, String> parseMetadata(String raw) {
        Map<String, String> metadata = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return metadata;
        }
        try {
            Map<String, Object> rawMap = mapper.readValue(raw, Map.class);
            rawMap.forEach((k, v) -> metadata.put(String.valueOf(k), String.valueOf(v)));
        } catch (IOException e) {
            log.warn("解析记忆元数据失败: {}", e.getMessage());
        }
        return metadata;
    }

    static File resolveStorageDir() {
        String configured = EnvLoader.get("AGENTCLI_MEMORY_DIR");
        if (configured != null && !configured.isBlank()) {
            return new File(configured.trim());
        }
        return AppConfig.CONFIG_DIR.resolve("memory").toFile();
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map<?, ?> metaMap) {
                metaMap.forEach((k, v) -> metadata.put(String.valueOf(k), String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n
                    ? n.intValue()
                    : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
