package com.agentcli.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentcli.tool.search.CodeSearchEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 索引编排器：把一棵源码树变成「向量块 + 关系图谱」两张表。
 *
 * <p>流程与关键取舍：
 * <ol>
 *     <li><b>两遍扫描</b>——第一遍收集项目内类型名，第二遍才抽取关系，
 *         否则无法把 {@code list.add()} 这类 JDK 调用挡在图谱之外；</li>
 *     <li><b>降级优先</b>——{@link EmbeddingClient#isAvailable()} 为 false 时<b>完全跳过分块与向量化</b>，
 *         只建结构索引；此时 {@code code_chunks} 为空，{@code /graph} 依然完整可用；</li>
 *     <li><b>熔断</b>——向量化连续失败 {@value #EMBED_FAILURE_THRESHOLD} 次即停止请求并退回结构索引模式，
 *         避免对着数百个块打数百次注定失败的 HTTP 请求；</li>
 *     <li><b>先全量收集、后一次落库</b>——所有数据在内存里备齐才 {@code clearProject + 批量插入}，
 *         中途失败不会把用户已有的旧索引清空。</li>
 * </ol>
 *
 * <p><b>不抛方法</b>：{@link #index(Path, ProgressListener)} 内部捕获全部异常，
 * 统一转成 {@code IndexResult(success=false, message=...)}，完整堆栈只进日志。
 */
public class CodeIndex {

    private static final Logger log = LoggerFactory.getLogger(CodeIndex.class);

    /** 向量化连续失败多少次后熔断。 */
    public static final int EMBED_FAILURE_THRESHOLD = 3;

    /** 单文件大小上限，超过则跳过（1MB）。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    /** 参与索引的扩展名白名单。 */
    private static final Set<String> INDEXED_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h",
            ".md", ".xml", ".yaml", ".yml", ".properties", ".sql", ".sh");

    private final VectorStore store;
    private final EmbeddingClient embedding;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;

    /** 使用默认分块器与关系抽取器。 */
    public CodeIndex(VectorStore store, EmbeddingClient embedding) {
        this(store, embedding, new CodeChunker(), new CodeAnalyzer());
    }

    /**
     * @param store     向量与关系存储
     * @param embedding embedding 客户端，永不为 null（不可用时由工厂返回空对象）
     * @param chunker   分块器
     * @param analyzer  关系抽取器
     */
    public CodeIndex(VectorStore store, EmbeddingClient embedding, CodeChunker chunker, CodeAnalyzer analyzer) {
        if (store == null || embedding == null || chunker == null || analyzer == null) {
            throw new IllegalArgumentException("CodeIndex 依赖项不得为 null");
        }
        this.store = store;
        this.embedding = embedding;
        this.chunker = chunker;
        this.analyzer = analyzer;
    }

    /**
     * 建立 / 重建索引。
     *
     * @param root     项目根目录
     * @param listener 进度回调；null 时使用 {@link ProgressListener#noop()}
     * @return 索引结果；<b>任何失败都以 {@code success=false} 返回，绝不抛异常</b>
     */
    public IndexResult index(Path root, ProgressListener listener) {
        long startedAt = System.currentTimeMillis();
        ProgressListener progress = listener == null ? ProgressListener.noop() : listener;
        try {
            if (root == null) {
                return IndexResult.failure("路径不存在: <空>");
            }
            Path normalized = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized)) {
                return IndexResult.failure("路径不存在: " + normalized);
            }

            progress.onProgress("📦 开始索引: " + normalized);
            List<Path> files = collectSourceFiles(normalized);
            progress.onProgress("   扫描文件 ... " + files.size() + " 个源文件");

            Reading reading = readAll(normalized, files);
            Set<String> knownTypes = collectKnownTypes(reading.contents());

            boolean vectorize = embedding.isAvailable();
            String degradeReason = null;
            if (!vectorize) {
                degradeReason = embedding.unavailableReason();
                progress.onProgress("⚠️ 未检测到 embedding 配置，跳过向量化，仅建立结构索引（类关系图谱可用）");
                progress.onProgress("   提示：在 ~/.agentcli/config.json 配置 embedding 后重新 /index 可启用语义检索");
            }

            Vectorization vectorization = buildEntriesAndRelations(reading, knownTypes, vectorize, progress);
            if (vectorization.degradeReason() != null) {
                degradeReason = vectorization.degradeReason();
            }
            if (vectorization.vectorized()) {
                progress.onProgress("   向量化 ....... " + vectorization.entries().size()
                        + "/" + vectorization.totalChunks());
            }

            String projectKey = VectorStore.projectKey(normalized);
            VectorStore.WriteResult written =
                    store.replaceProject(projectKey, vectorization.entries(), vectorization.relations());
            progress.onProgress("   写入索引 ..... " + written.chunkCount() + " 块 / "
                    + written.relationCount() + " 条关系");

            long elapsed = System.currentTimeMillis() - startedAt;
            return new IndexResult(true, files.size(), written.chunkCount(), written.relationCount(),
                    reading.failedFileCount(), elapsed, vectorization.vectorized(), degradeReason, "");
        } catch (Exception e) {
            log.warn("索引失败: {}", root, e);
            return IndexResult.failure(brief(e));
        }
    }

    /**
     * 索引新鲜度检测：用源码树的最大修改时间与文件数对比索引快照，不额外建表。
     *
     * @return 过期时 {@code stale=true} 并附带一行提示；无索引或检测失败一律视为不过期
     */
    public Freshness checkFreshness(Path root, VectorStore.IndexStats stats) {
        if (root == null || stats == null || (stats.chunkCount() == 0 && stats.relationCount() == 0)) {
            return Freshness.fresh();
        }
        try {
            Path normalized = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized)) {
                return Freshness.fresh();
            }
            List<Path> files = collectSourceFiles(normalized);
            long newest = 0L;
            for (Path file : files) {
                try {
                    newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // 单个文件读不到时间不影响整体判断
                }
            }
            if (stats.indexedAt() > 0L && newest > stats.indexedAt()) {
                return new Freshness(true, "⚠️ 索引可能已过期（有源文件在索引之后被修改），建议重新执行 /index");
            }
            if (stats.fileCount() > 0 && files.size() != stats.fileCount()) {
                return new Freshness(true, "⚠️ 索引覆盖的文件数与当前源码树不一致，建议重新执行 /index");
            }
            return Freshness.fresh();
        } catch (Exception e) {
            log.warn("索引新鲜度检测失败: {}", root, e);
            return Freshness.fresh();
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /** 遍历源码树，套用排除目录、点目录、扩展名白名单与 1MB 大小上限。 */
    private List<Path> collectSourceFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.startsWith(".") || CodeSearchEngine.EXCLUDED_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES && isIndexable(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("遍历文件失败，跳过: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Path::compareTo);
        return files;
    }

    private static boolean isIndexable(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && INDEXED_EXTENSIONS.contains(name.substring(dot));
    }

    /** 一次性读入全部文件内容（两遍扫描共用），读取失败只累加计数不中断。 */
    private Reading readAll(Path root, List<Path> files) {
        Map<String, String> contents = new LinkedHashMap<>();
        int failed = 0;
        for (Path file : files) {
            try {
                contents.put(relativize(root, file), Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                failed++;
                log.warn("读取源文件失败，跳过: {}", file, e);
            }
        }
        return new Reading(contents, failed);
    }

    /** 第一遍扫描：收集全项目 Java 类型简单名。 */
    private static Set<String> collectKnownTypes(Map<String, String> contents) {
        Set<String> knownTypes = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                knownTypes.addAll(CodeAnalyzer.collectTypeNames(entry.getValue()));
            }
        }
        return knownTypes;
    }

    /** 第二遍扫描：按需向量化 + 抽取关系，全部在内存中收集。 */
    private Vectorization buildEntriesAndRelations(Reading reading, Set<String> knownTypes,
                                                   boolean vectorizeEnabled, ProgressListener progress) {
        List<VectorStore.ChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        boolean vectorize = vectorizeEnabled;
        boolean vectorized = vectorizeEnabled;
        String degradeReason = null;
        int consecutiveFailures = 0;
        int totalChunks = 0;

        for (Map.Entry<String, String> file : reading.contents().entrySet()) {
            String relativePath = file.getKey();
            String content = file.getValue();

            if (vectorize) {
                List<CodeChunk> chunks = chunker.chunkText(relativePath, content);
                totalChunks += chunks.size();
                for (CodeChunk chunk : chunks) {
                    try {
                        float[] vector = embedding.embed(chunk.toEmbeddingText());
                        if (vector == null || vector.length == 0) {
                            consecutiveFailures++;
                        } else {
                            entries.add(new VectorStore.ChunkEntry(chunk, vector, embedding.modelName()));
                            consecutiveFailures = 0;
                            continue;
                        }
                    } catch (IOException | RuntimeException e) {
                        consecutiveFailures++;
                        log.warn("向量化失败: {}", chunk.location(), e);
                    }
                    if (consecutiveFailures >= EMBED_FAILURE_THRESHOLD) {
                        progress.onProgress("⚠️ 向量化连续失败，已停止向量化，继续建立结构索引");
                        // 熔断后清空已产出的向量块：保持「vectorized==false 即 chunkCount==0」的单一语义，
                        // 避免半截索引让 /search 走上语义分支却只覆盖前几个文件
                        entries.clear();
                        vectorize = false;
                        vectorized = false;
                        degradeReason = "embedding 请求连续失败";
                        break;
                    }
                }
            }

            if (relativePath.endsWith(".java")) {
                relations.addAll(analyzer.analyze(relativePath, content, knownTypes));
            }
        }
        return new Vectorization(entries, relations, vectorized, degradeReason, totalChunks);
    }

    /** 相对项目根的路径，分隔符统一为 {@code /}。 */
    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString().replace('\\', '/');
        }
    }

    /** 单行错误摘要，堆栈只进日志。 */
    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replace('\n', ' ').trim();
        return single.length() > 200 ? single.substring(0, 200) + "..." : single;
    }

    /** 文件读取阶段的中间产物。 */
    private record Reading(Map<String, String> contents, int failedFileCount) {
    }

    /** 向量化 + 关系抽取阶段的中间产物。 */
    private record Vectorization(List<VectorStore.ChunkEntry> entries, List<CodeRelation> relations,
                                 boolean vectorized, String degradeReason, int totalChunks) {
    }

    // ---------------------------------------------------------------- 嵌套类型

    /** 索引进度回调；CLI 侧通常直接传 {@code out::println}。 */
    @FunctionalInterface
    public interface ProgressListener {

        void onProgress(String message);

        /** 什么都不做的实现，供无需进度输出的调用方（如测试）使用。 */
        static ProgressListener noop() {
            return message -> {
            };
        }
    }

    /**
     * 索引结果。
     *
     * @param vectorized    是否真正建立了向量索引（false 表示只建了结构索引）
     * @param degradeReason 降级原因，未降级为 null
     * @param message       失败原因；成功时为空串
     */
    public record IndexResult(boolean success, int fileCount, int chunkCount, int relationCount,
                              int failedFileCount, long elapsedMs, boolean vectorized,
                              String degradeReason, String message) {

        /** 失败结果的统一构造入口。 */
        public static IndexResult failure(String message) {
            return new IndexResult(false, 0, 0, 0, 0, 0L, false, null, message);
        }
    }

    /** 索引新鲜度。 */
    public record Freshness(boolean stale, String hint) {

        /** 不过期。 */
        public static Freshness fresh() {
            return new Freshness(false, null);
        }
    }
}
