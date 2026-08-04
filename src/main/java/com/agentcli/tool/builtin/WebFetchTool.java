package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolSchema;
import com.agentcli.web.WebFetchService;

import java.util.Map;

/**
 * 网页抓取工具：给定 URL，返回整理成 Markdown 的正文。
 *
 * <p>与既有 {@code web_search} 组成闭环——搜索拿到链接，抓取读到全文，
 * 模型才能基于原文归纳而不是靠 200 字的摘要猜。
 *
 * <p>本类是**纯工具外壳**：参数 Schema、参数钳制、把结构化结果排版成给模型看的文本。
 * 分派、提取、截断等业务逻辑一律在 {@link WebFetchService} 里，
 * 这样工具层永远只有「读参数 → 调一次 → 排版」三步，好读也好测。
 */
public class WebFetchTool extends AbstractTool {

    /** 默认字符预算：两万字够读完绝大多数技术文档页，又不至于一次吃掉半个上下文窗口。 */
    private static final int DEFAULT_MAX_CHARS = 20_000;
    private static final int MIN_MAX_CHARS = 1_000;
    private static final int MAX_MAX_CHARS = 60_000;

    private volatile WebFetchService service;

    public WebFetchTool() {
        super(
                "web_fetch",
                "抓取指定网页并提取正文为 Markdown（只读）。适合在 web_search 拿到链接后深入阅读整页内容，"
                        + "支持 HTML / 纯文本 / JSON，自动剔除导航、侧栏、页脚等噪声。"
                        + "只支持 http:// 与 https:// 公网地址，内网与本机地址会被安全策略拒绝。",
                ToolSchema.object(
                        ToolSchema.required("url", "string", "要抓取的网页 URL，必须以 http:// 或 https:// 开头"),
                        ToolSchema.optional("max_chars", "integer", "返回正文的最大字符数，默认 20000，下限 1000，上限 60000")
                ));
    }

    /** 测试用：注入桩件服务，跳过真实 HTTP 抓取。 */
    WebFetchTool(WebFetchService service) {
        this();
        this.service = service;
    }

    @Override
    public String execute(Map<String, String> args) {
        String url = args.get("url");
        if (url == null || url.isBlank()) {
            return "❌ 抓取失败: url 不能为空";
        }
        int maxChars = ToolArgs.clamp(
                ToolArgs.parseInt(args, "max_chars", DEFAULT_MAX_CHARS), MIN_MAX_CHARS, MAX_MAX_CHARS);

        WebFetchService.Outcome outcome = service().fetch(url.trim(), maxChars);
        if (!outcome.success()) {
            return "❌ 抓取失败: " + outcome.errorMessage();
        }
        return render(outcome, maxChars);
    }

    /**
     * 懒加载服务实例。
     *
     * <p>与 {@code WebSearchTool.provider()} 同一套双重检查锁：构造期不碰任何外部资源，
     * 保证 {@code new ToolRegistry(...)} 依旧是纯内存操作。
     */
    private WebFetchService service() {
        WebFetchService current = service;
        if (current == null) {
            synchronized (this) {
                if (service == null) {
                    service = new WebFetchService();
                }
                current = service;
            }
        }
        return current;
    }

    /**
     * 排版成给模型看的文本。
     *
     * <p>元信息（标题 / 最终 URL / 截断说明）集中在正文之前，空行隔开，
     * 模型读前几行就能判断「这页是不是我要的、内容全不全」，不必扫完全文。
     */
    private String render(WebFetchService.Outcome outcome, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 ").append(outcome.title()).append('\n');
        sb.append("🔗 ").append(outcome.finalUrl());
        if (outcome.redirected()) {
            sb.append("（经 ").append(outcome.redirectCount()).append(" 跳重定向）");
        }
        sb.append('\n');

        if (outcome.truncatedByChars()) {
            sb.append("⚠️ 内容已截断（原始 ").append(outcome.originalChars())
                    .append(" 字符，返回前 ").append(maxChars).append(" 字符）\n");
        }
        if (outcome.truncatedByBytes()) {
            sb.append("⚠️ 响应体超过体积上限，远端内容未读完整\n");
        }

        sb.append('\n');
        String markdown = outcome.markdown().strip();
        sb.append(markdown.isEmpty() ? "（未提取到正文内容）" : markdown);
        return sb.toString();
    }
}
