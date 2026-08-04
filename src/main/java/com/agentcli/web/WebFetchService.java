package com.agentcli.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;

/**
 * 网页抓取的业务编排层。
 *
 * <p>站在 {@link WebFetcher}（只懂传输）与 {@link WebFetchTool 工具外壳}（只懂排版）之间，
 * 承担四件事，且**只**承担这四件事：
 * <ol>
 *     <li>按 Content-Type 分派：HTML 走正文提取器，JSON / 纯文本原文直通，其余拒绝；</li>
 *     <li>按 {@code maxChars} 做字符预算截断；</li>
 *     <li>把「字节截断」（传输层，2MB 上限）与「字符截断」（本层，模型上下文预算）
 *         两种语义分开汇报——它们成因不同，模型看到后能做的决策也不同；</li>
 *     <li>把成功 / 失败统一收敛成 {@link Outcome}，<b>绝不向上抛异常</b>。</li>
 * </ol>
 *
 * <p><b>为什么单独存在而不让工具直接调 Fetcher</b>：上面这四条是有分支、有状态的真实逻辑，
 * 塞进工具外壳会让「参数解析 + 业务 + 排版」搅在一起，既胖又只能靠起 HTTP 服务才能测；
 * 拆出来后 {@link #fetch} 返回结构化结果，脱离工具层就能单测。
 *
 * <p>判据：把 {@link WebFetcher} 换成别的 HTTP 实现、把 {@link HtmlToMarkdownExtractor}
 * 换成别的算法，本类都一行不用改。
 */
public class WebFetchService {

    private static final Logger log = LoggerFactory.getLogger(WebFetchService.class);

    /** 调用方未给出有效预算时的默认字符上限。 */
    public static final int DEFAULT_MAX_CHARS = 20_000;

    /** 失败原因摘要的最大长度，避免把长堆栈信息整段塞进模型上下文。 */
    private static final int MAX_ERROR_CHARS = 200;

    private final WebFetcher fetcher;
    private final HtmlToMarkdownExtractor extractor;

    /** 生产入口：默认围栏（禁回环）+ 默认提取器。 */
    public WebFetchService() {
        this(new WebFetcher(), new HtmlToMarkdownExtractor());
    }

    /** 测试用：注入桩件，既不起 HTTP 也不跑真实提取。 */
    WebFetchService(WebFetcher fetcher, HtmlToMarkdownExtractor extractor) {
        if (fetcher == null || extractor == null) {
            throw new IllegalArgumentException("WebFetchService 依赖项不得为 null");
        }
        this.fetcher = fetcher;
        this.extractor = extractor;
    }

    /**
     * 抓取并整理一个网页。
     *
     * @param url      目标 URL
     * @param maxChars 正文字符预算；小于等于 0 时取 {@link #DEFAULT_MAX_CHARS}
     * @return 成功或失败的结构化结果；<b>绝不抛异常</b>
     */
    public Outcome fetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return Outcome.fail("url 不能为空");
        }
        int budget = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;

        FetchResult result;
        try {
            result = fetcher.fetch(url.trim());
        } catch (IOException | RuntimeException e) {
            String reason = brief(e);
            log.warn("网页抓取失败: {} -> {}", url.trim(), reason);
            return Outcome.fail(reason);
        }

        String title;
        String content;
        if (result.isHtml()) {
            HtmlToMarkdownExtractor.Extracted extracted =
                    extractor.extract(result.body(), result.finalUrl());
            title = extracted.title();
            content = extracted.markdown();
        } else if (isPassThrough(result)) {
            // JSON / 纯文本 / Markdown / 无 Content-Type：原文本身就是最好的表示，
            // 强行转 Markdown 只会破坏缩进与结构（API 文档场景尤其明显）。
            title = "";
            content = result.body().strip();
        } else {
            // 传输层白名单通常已经拦掉这类响应；这里再挡一层，
            // 好处是本层的分派逻辑自洽，不依赖上游是否恰好也做了校验。
            return Outcome.fail("不支持的内容类型: " + result.mimeType() + "（仅支持 HTML、纯文本、Markdown、JSON）");
        }

        if (title == null || title.isBlank()) {
            title = fallbackTitle(result.finalUrl());
        }
        if (content == null) {
            content = "";
        }

        int originalChars = content.length();
        boolean truncatedByChars = originalChars > budget;
        String markdown = truncatedByChars ? safeCut(content, budget) : content;

        return Outcome.ok(result.finalUrl(), title, markdown, originalChars,
                result.truncated(), truncatedByChars, result.redirectChain().size());
    }

    // ---------------------------------------------------------------- 内部实现

    /** 是否属于「原文直通」类型：JSON、任意 text/*，以及缺失 Content-Type 的响应。 */
    private static boolean isPassThrough(FetchResult result) {
        if (result.isJson()) {
            return true;
        }
        String mime = result.mimeType();
        return mime.isEmpty() || mime.startsWith("text/");
    }

    /**
     * 按字符预算裁剪，且不把一对代理项（surrogate pair）劈开。
     *
     * <p>emoji 与部分汉字在 Java 里占两个 {@code char}，从中间切断会产出乱码字符，
     * 少留一个字符换来输出永远合法，这笔账划算。
     */
    private static String safeCut(String text, int budget) {
        int end = Math.min(budget, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** 没有标题时退化成域名，让模型至少知道内容出处。 */
    private static String fallbackTitle(String finalUrl) {
        if (finalUrl == null || finalUrl.isBlank()) {
            return "(无标题)";
        }
        try {
            String host = java.net.URI.create(finalUrl).getHost();
            return host == null || host.isBlank() ? "(无标题)" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "(无标题)";
        }
    }

    /** 异常摘要：单行、限长、永不为空。 */
    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() > MAX_ERROR_CHARS
                ? single.substring(0, MAX_ERROR_CHARS) + "..."
                : single;
    }

    // ---------------------------------------------------------------- 嵌套类型

    /**
     * 一次抓取编排的结果。
     *
     * <p>两个截断标志刻意分开：{@code truncatedByBytes} 说明「远端内容太大，后半段根本没读」，
     * {@code truncatedByChars} 说明「内容读全了，但为了控制上下文只回传前一段」。
     * 前者模型无能为力，后者模型可以要求调大 {@code max_chars} 再读一次。
     *
     * @param success          是否成功
     * @param errorMessage     失败原因；成功时为空串
     * @param finalUrl         跟完重定向后的最终 URL
     * @param title            页面标题；无标题时退化为域名
     * @param markdown         整理后的正文（已按字符预算截断）
     * @param originalChars    截断前的正文字符数
     * @param truncatedByBytes 是否在传输层因字节上限被截断
     * @param truncatedByChars 是否在本层因字符预算被截断
     * @param redirectCount    重定向跳数；无重定向为 0
     */
    public record Outcome(
            boolean success,
            String errorMessage,
            String finalUrl,
            String title,
            String markdown,
            int originalChars,
            boolean truncatedByBytes,
            boolean truncatedByChars,
            int redirectCount) {

        public Outcome {
            errorMessage = errorMessage == null ? "" : errorMessage;
            finalUrl = finalUrl == null ? "" : finalUrl;
            title = title == null ? "" : title;
            markdown = markdown == null ? "" : markdown;
        }

        /** 成功结果。 */
        public static Outcome ok(String finalUrl, String title, String markdown, int originalChars,
                                 boolean truncatedByBytes, boolean truncatedByChars, int redirectCount) {
            return new Outcome(true, "", finalUrl, title, markdown, originalChars,
                    truncatedByBytes, truncatedByChars, Math.max(redirectCount, 0));
        }

        /** 失败结果。 */
        public static Outcome fail(String errorMessage) {
            return new Outcome(false, errorMessage, "", "", "", 0, false, false, 0);
        }

        /** 是否发生过任意一种截断。 */
        public boolean truncated() {
            return truncatedByBytes || truncatedByChars;
        }

        /** 是否经历过重定向。 */
        public boolean redirected() {
            return redirectCount > 0;
        }
    }
}
