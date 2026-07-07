package com.agentcli.memory;

import com.agentcli.common.AppConfig;
import com.agentcli.common.EnvLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 长期记忆：跨会话持久化的关键事实与偏好，落盘到 {@code ~/.agentcli/memory/}。
 */
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_FILE = "long_term_memory.json";

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);
        loadFromDisk();
    }

    public void store(MemoryEntry entry) {
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }
        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());
        saveToDisk();
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
            saveToDisk();
            return true;
        }
        return false;
    }

    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
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

    private void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) {
            return;
        }
        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage());
        }
    }

    static File resolveStorageDir() {
        String configured = EnvLoader.get("AGENTCLI_MEMORY_DIR");
        if (configured != null && !configured.isBlank()) {
            return new File(configured.trim());
        }
        return AppConfig.CONFIG_DIR.resolve("memory").toFile();
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
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
