package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolSchema;
import com.agentcli.web.SearchProvider;
import com.agentcli.web.SearchProviderFactory;
import com.agentcli.web.SearchResult;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具：通过 {@link SearchProvider} 抽象获取实时信息（最新版本、官方文档、技术资讯等）。
 *
 * 两个工程化要点：
 * - 优雅降级：provider 未就绪（缺 Key / 地址非法）时返回可操作的配置提示，而非抛错让对话中断
 * - 结果截断：单条 snippet 截断到 {@value #MAX_SNIPPET_CHARS} 字符，控制塞回模型的上下文体积
 */
public class WebSearchTool extends AbstractTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_SNIPPET_CHARS = 200;

    private volatile SearchProvider provider;

    public WebSearchTool() {
        super(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。"
                        + "支持智谱（默认，与 GLM 共用 Key）、SerpAPI、自托管 SearXNG，由 SEARCH_PROVIDER 切换。",
                ToolSchema.object(
                        ToolSchema.required("query", "string", "搜索关键词，例如 'Java 21 新特性'、'Spring Boot 3.3 release notes'"),
                        ToolSchema.optional("top_k", "integer", "返回结果数量（默认 5）")
                ));
    }

    /** 测试用：注入指定 provider，跳过基于环境变量的工厂创建。 */
    WebSearchTool(SearchProvider provider) {
        this();
        this.provider = provider;
    }

    @Override
    public String execute(Map<String, String> args) {
        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        int topK = ToolArgs.parseInt(args, "top_k", DEFAULT_TOP_K);

        SearchProvider current = provider();
        if (!current.isReady()) {
            return "⚠️ " + current.unavailableHint();
        }
        try {
            List<SearchResult> results = current.search(query.trim(), topK);
            return format(current.name(), query.trim(), results);
        } catch (Exception e) {
            return "搜索失败 (" + current.name() + "): " + e.getMessage();
        }
    }

    private SearchProvider provider() {
        SearchProvider current = provider;
        if (current == null) {
            synchronized (this) {
                if (provider == null) {
                    provider = SearchProviderFactory.create();
                }
                current = provider;
            }
        }
        return current;
    }

    private String format(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > MAX_SNIPPET_CHARS) {
                    snippet = snippet.substring(0, MAX_SNIPPET_CHARS) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
