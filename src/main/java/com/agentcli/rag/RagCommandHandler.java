package com.agentcli.rag;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import com.agentcli.common.AppConfig;
import com.agentcli.store.SqliteStore;
import com.agentcli.tool.search.CodeSearchEngine;
import com.agentcli.tool.search.RipgrepCodeSearchEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /index}、{@code /search}、{@code /graph} 三命令的门面。
 *
 * <p>把「装配依赖 + 参数校验 + 调用编排层 + 排版 + 异常兜底」全部收在这一层，
 * 让 {@code InteractiveSession} 退化成纯派发——与既有 {@code LspCommandHandler}
 * 的组织方式保持一致。
 *
 * <p><b>三条硬约束</b>：
 * <ul>
 *     <li><b>不抛方法</b>：三个 {@code handle*} 签名均不带 {@code throws}，
 *         任何异常都收敛成单行 {@code ❌ <动作>失败: <原因>}，完整堆栈只进日志；</li>
 *     <li><b>{@code /graph} 与 embedding 彻底解耦</b>：前置校验只读
 *         {@link VectorStore.IndexStats#relationCount()}，<b>从不触碰 {@code chunkCount()}</b>。
 *         没有 embedding key 时向量表为空，但关系图谱依旧完整可用，若误用 {@code chunkCount()}
 *         做前置拦截，就会把一个本可用的功能拦死；</li>
 *     <li><b>无跨模块副作用</b>：{@code /index <路径>} 只影响该次索引写入的 {@code project_path}，
 *         不修改会话项目根；目标路径与会话根不一致时补一行提示，避免用户误以为
 *         {@code /search} 会跟着切换。</li>
 * </ul>
 */
public class RagCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(RagCommandHandler.class);

    /**
     * 最近一次 {@link #create} 失败的原因。
     *
     * <p>{@code create()} 按设计返回 {@code null} 而非抛异常，调用方无从得知失败缘由；
     * 这里留一个只读快照，让 CLI 能打印出 {@code ❌ RAG 索引不可用: <原因>} 而不是一句干巴巴的通用错误。
     */
    private static volatile String lastCreateFailure = "";

    private final CodeIndex codeIndex;
    private final CodeRetriever retriever;
    private final Path sessionProjectRoot;
    private final String sessionProjectKey;

    /** 降级提示本会话只打印一次：每轮检索都重复同一句 ⚠️ 只会让用户对警告脱敏。 */
    private boolean degradeNoticeShown;

    /**
     * @param codeIndex          索引编排器
     * @param retriever          混合检索器
     * @param sessionProjectRoot 会话项目根；{@code null} 时取当前工作目录
     */
    public RagCommandHandler(CodeIndex codeIndex, CodeRetriever retriever, Path sessionProjectRoot) {
        if (codeIndex == null || retriever == null) {
            throw new IllegalArgumentException("RagCommandHandler 依赖项不得为 null");
        }
        this.codeIndex = codeIndex;
        this.retriever = retriever;
        this.sessionProjectRoot = normalize(sessionProjectRoot);
        this.sessionProjectKey = VectorStore.projectKey(this.sessionProjectRoot);
    }

    /**
     * 按配置装配一套完整的 RAG 组件。
     *
     * <p>存储初始化失败时<b>返回 {@code null} 而不是抛异常</b>：RAG 是增强能力，
     * 打不开索引库不该让整个 REPL 崩掉，退化成「该命令不可用」即可。
     *
     * @param config      应用配置，可为 null（embedding 将退化为禁用空对象）
     * @param projectPath 会话项目根路径
     * @return 门面实例；初始化失败返回 {@code null}，原因见 {@link #lastCreateFailure()}
     */
    public static RagCommandHandler create(AppConfig config, String projectPath) {
        try {
            VectorStore vectorStore = new VectorStore(SqliteStore.defaultStore());
            EmbeddingClient embedding = EmbeddingClientFactory.create(config);
            CodeIndex index = new CodeIndex(vectorStore, embedding);
            CodeRetriever retriever = new CodeRetriever(vectorStore, embedding,
                    new RipgrepCodeSearchEngine(CodeSearchEngine.EXCLUDED_DIRS));
            lastCreateFailure = "";
            return new RagCommandHandler(index, retriever, toPath(projectPath));
        } catch (SQLException | RuntimeException e) {
            lastCreateFailure = brief(e);
            log.warn("RAG 组件初始化失败，/index /search /graph 将不可用", e);
            return null;
        }
    }

    /** 最近一次 {@link #create} 失败的原因摘要；从未失败过时返回空串。 */
    public static String lastCreateFailure() {
        return lastCreateFailure;
    }

    // ---------------------------------------------------------------- /index

    /**
     * 建立 / 重建索引。
     *
     * @param payload  可选目标路径；空表示会话项目根
     * @param listener 进度回调，CLI 侧通常传 {@code out::println}
     * @return 单段结果文本；<b>绝不抛异常</b>
     */
    public String handleIndex(String payload, CodeIndex.ProgressListener listener) {
        try {
            Path target = resolveTarget(payload);
            CodeIndex.IndexResult result = codeIndex.index(target, listener);
            if (!result.success()) {
                return "❌ 索引失败: " + result.message();
            }

            StringBuilder sb = new StringBuilder();
            String elapsed = String.format(Locale.ROOT, "%.1fs", result.elapsedMs() / 1000.0);
            if (result.vectorized()) {
                sb.append("✅ 索引完成：")
                        .append(result.fileCount()).append(" 文件 / ")
                        .append(result.chunkCount()).append(" 块 / ")
                        .append(result.relationCount()).append(" 条关系 / 耗时 ").append(elapsed);
            } else {
                sb.append("✅ 结构索引完成：")
                        .append(result.fileCount()).append(" 文件 / ")
                        .append(result.relationCount()).append(" 条关系 / 耗时 ").append(elapsed);
            }
            if (result.failedFileCount() > 0) {
                sb.append('\n').append("   ").append(result.failedFileCount()).append(" 个文件读取失败已跳过");
            }
            if (!sessionProjectRoot.equals(target)) {
                sb.append('\n').append("   提示：本次索引的是 ").append(target)
                        .append("，/search 与 /graph 仍作用于当前会话项目 ").append(sessionProjectRoot);
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("索引命令执行失败: {}", payload, e);
            return "❌ 索引失败: " + brief(e);
        }
    }

    // ---------------------------------------------------------------- /search

    /**
     * 混合检索代码。
     *
     * @param payload 自然语言查询；空则返回用法提示
     * @return 排版后的结果文本；<b>绝不抛异常</b>
     */
    public String handleSearch(String payload) {
        if (isBlank(payload)) {
            return "❌ 请提供检索关键词，例如 /search 用户登录实现";
        }
        String query = payload.trim();
        try {
            CodeRetriever.SearchOutcome outcome = retriever.search(
                    sessionProjectKey, sessionProjectRoot, query, CodeRetriever.DEFAULT_TOP_K);
            return SearchResultFormatter.format(query, applyNoticePolicy(outcome));
        } catch (RuntimeException e) {
            log.warn("检索命令执行失败: {}", query, e);
            return "❌ 检索失败: " + brief(e);
        }
    }

    // ---------------------------------------------------------------- /graph

    /**
     * 查询类关系图谱。
     *
     * <p>整条链路不经过 embedding：前置校验只看关系表条数，检索走
     * {@link CodeRetriever#relations}，排版走 {@link RelationGraphFormatter}。
     *
     * @param payload 类名或 {@code Type.method}；空则返回用法提示
     * @return 树形图谱文本；<b>绝不抛异常</b>
     */
    public String handleGraph(String payload) {
        if (isBlank(payload)) {
            return "❌ 请提供类名，例如 /graph ReactAgent";
        }
        String name = payload.trim();
        try {
            // 只读 relationCount：/graph 不依赖向量，读 chunkCount 会把无 key 场景误拦成「未建索引」
            if (retriever.stats(sessionProjectKey).relationCount() == 0) {
                return RelationGraphFormatter.formatEmpty(name);
            }
            List<CodeRelation> relations = retriever.relations(sessionProjectKey, name);
            return RelationGraphFormatter.format(name, relations);
        } catch (RuntimeException e) {
            log.warn("类关系图谱查询失败: {}", name, e);
            return "❌ 查询类关系失败: " + brief(e);
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 一次性降级提示策略：首次降级原样透出 {@code ⚠️} 提示，之后把降级原因抹成
     * {@link CodeRetriever.DegradeReason#NONE}，让排版器自然跳过提示行，只留结果。
     */
    private CodeRetriever.SearchOutcome applyNoticePolicy(CodeRetriever.SearchOutcome outcome) {
        if (outcome == null || !outcome.degraded()) {
            return outcome;
        }
        if (degradeNoticeShown) {
            return new CodeRetriever.SearchOutcome(
                    outcome.mode(), CodeRetriever.DegradeReason.NONE, null, outcome.results());
        }
        degradeNoticeShown = true;
        return outcome;
    }

    /** 解析 {@code /index} 的目标路径：相对路径按会话项目根解析，缺省即会话项目根。 */
    private Path resolveTarget(String payload) {
        if (isBlank(payload)) {
            return sessionProjectRoot;
        }
        Path candidate = Path.of(payload.trim());
        Path resolved = candidate.isAbsolute() ? candidate : sessionProjectRoot.resolve(candidate);
        return resolved.toAbsolutePath().normalize();
    }

    private static Path toPath(String projectPath) {
        return isBlank(projectPath) ? null : Path.of(projectPath);
    }

    private static Path normalize(Path path) {
        Path base = path == null ? Path.of("") : path;
        return base.toAbsolutePath().normalize();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 折成单行、限长的原因摘要——用户界面永远只出一行。 */
    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replace('\n', ' ').trim();
        return single.length() > 120 ? single.substring(0, 120) + "..." : single;
    }
}
