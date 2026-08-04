package com.agentcli.tool.builtin;

import com.agentcli.common.AppConfig;
import com.agentcli.rag.CodeRetriever;
import com.agentcli.rag.EmbeddingClient;
import com.agentcli.rag.EmbeddingClientFactory;
import com.agentcli.rag.SearchResultFormatter;
import com.agentcli.rag.VectorStore;
import com.agentcli.store.SqliteStore;
import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;
import com.agentcli.tool.search.CodeSearchEngine;
import com.agentcli.tool.search.RipgrepCodeSearchEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

/**
 * 语义检索工具：把既有的 {@code /search} 检索能力开放给模型自主调用。
 *
 * <p>与 {@code grep_code} 是互补而非重复的两条路：关键词检索要求你已经知道要找什么串，
 * 语义检索回答的是「这个功能在哪儿实现的」——两者的选择策略写进了 description，
 * 否则模型只会凭手感乱挑。
 *
 * <p><b>三个关键实现选择</b>：
 * <ol>
 *     <li><b>必须懒加载</b>。工具在 {@code ToolRegistry} 构造器里被同步创建，全仓有二十多处
 *         {@code new ToolRegistry(...)}；若构造期就打开 SQLite，每建一次注册表就开一次库，
 *         测试会大面积变慢。因此首次 {@code execute} 才装配，用双重检查锁保证只装配一次。</li>
 *     <li><b>装配失败缓存原因，不重试</b>。打不开索引库通常是环境性问题，
 *         每次调用重试一遍只是把同一个错误重复付费；缓存后直接自述不可用并给出替代方案。</li>
 *     <li><b>每次调用都完整透出降级信息</b>。CLI 侧的 {@code /search} 只在首次显示降级提示
 *         （避免刷屏），但模型是逐轮独立读取工具结果的，第二轮起抹掉 {@code ⚠️}
 *         会让它误以为语义检索已经生效，从而高估结果可信度。</li>
 * </ol>
 */
public class RagSearchTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RagSearchTool.class);

    private static final int DEFAULT_TOP_K = 5;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    /** 装配失败原因摘要的最大长度。 */
    private static final int MAX_FAILURE_CHARS = 160;

    private final ToolContext context;

    private volatile CodeRetriever retriever;

    /** 装配失败原因；空串表示「尚未失败过」，非空表示已失败且不再重试。 */
    private volatile String initFailure = "";

    public RagSearchTool(ToolContext context) {
        super(
                "rag_search",
                "语义检索项目代码，回答「某功能是怎么实现的 / 在哪里」这类自然语言、不知道确切符号名的问题。"
                        + "先建索引（/index）效果最佳，无索引时自动降级为关键词兜底并在结果里说明。"
                        + "已知确切类名 / 方法名 / 字符串时请改用 grep_code（更快更准）。",
                ToolSchema.object(
                        ToolSchema.required("query", "string", "自然语言描述，例如「用户登录是怎么实现的」"),
                        ToolSchema.optional("top_k", "integer", "返回结果条数，默认 5，上限 20")
                ));
        if (context == null) {
            throw new IllegalArgumentException("RagSearchTool 依赖的 ToolContext 不得为 null");
        }
        this.context = context;
    }

    /** 测试用：注入假检索器，既不开 SQLite 也不调 embedding。 */
    RagSearchTool(ToolContext context, CodeRetriever retriever) {
        this(context);
        this.retriever = retriever;
    }

    @Override
    public String execute(Map<String, String> args) {
        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "❌ 检索失败: query 不能为空";
        }
        int topK = ToolArgs.clamp(ToolArgs.parseInt(args, "top_k", DEFAULT_TOP_K), MIN_TOP_K, MAX_TOP_K);

        CodeRetriever current = retriever();
        if (current == null) {
            return "⚠️ 语义检索不可用: " + initFailure + "；请改用 grep_code 关键词检索";
        }

        Path projectRoot = context.projectPath();
        String projectKey = VectorStore.projectKey(projectRoot);
        // search() 明确承诺不抛异常，且内部已含完整降级树，这里无需再包 try。
        CodeRetriever.SearchOutcome outcome = current.search(projectKey, projectRoot, query.trim(), topK);
        return SearchResultFormatter.format(query.trim(), outcome);
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 懒加载检索器。
     *
     * <p>{@code initFailure} 非空即代表「装配过且失败了」，此时直接返回 null 不再重试。
     *
     * @return 检索器；装配失败返回 {@code null}，原因见 {@link #initFailure}
     */
    private CodeRetriever retriever() {
        CodeRetriever current = retriever;
        if (current == null) {
            synchronized (this) {
                if (retriever == null && initFailure.isEmpty()) {
                    retriever = createRetriever();
                }
                current = retriever;
            }
        }
        return current;
    }

    /**
     * 装配一套检索组件。
     *
     * <p>与 {@code RagCommandHandler.create} 装配同样的四件套，但**不复用它**：
     * 那个门面绑定会话根路径，且带有「降级提示只显示一次」的交互策略，
     * 两点都与工具语义冲突。
     *
     * @return 检索器；失败时记录原因并返回 {@code null}
     */
    private CodeRetriever createRetriever() {
        try {
            AppConfig config = AppConfig.load();
            VectorStore store = new VectorStore(SqliteStore.defaultStore());
            EmbeddingClient embedding = EmbeddingClientFactory.create(config);
            return new CodeRetriever(store, embedding,
                    new RipgrepCodeSearchEngine(CodeSearchEngine.EXCLUDED_DIRS));
        } catch (SQLException | RuntimeException e) {
            initFailure = brief(e);
            log.warn("语义检索组件装配失败，rag_search 本次会话内不可用: {}", initFailure);
            return null;
        }
    }

    /** 异常摘要：单行、限长、永不为空（空消息时退化为异常类名）。 */
    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() > MAX_FAILURE_CHARS
                ? single.substring(0, MAX_FAILURE_CHARS) + "..."
                : single;
    }
}
