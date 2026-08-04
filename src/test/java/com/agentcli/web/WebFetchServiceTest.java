package com.agentcli.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排层用例（QA 独立补充）。
 *
 * <p>{@code WebFetchService} 原本没有直属用例：它的分派分支、双截断语义、标题兜底
 * 只能通过工具层或提取器用例<b>间接</b>触达，而那两处都对这些分支做了桩化，
 * 等于没测到。本类用桩件抓取器直接驱动编排逻辑，不起 HTTP、不跑真实提取。
 */
class WebFetchServiceTest {

    /** 桩件抓取器：直接吐出预置的 {@link FetchResult} 或预置异常。 */
    private static final class StubFetcher extends WebFetcher {

        private final FetchResult result;
        private final IOException error;

        StubFetcher(FetchResult result) {
            super(new NetworkPolicy());
            this.result = result;
            this.error = null;
        }

        StubFetcher(IOException error) {
            super(new NetworkPolicy());
            this.result = null;
            this.error = error;
        }

        @Override
        public FetchResult fetch(String url) throws IOException {
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private static FetchResult result(String contentType, String body, boolean truncatedBytes) {
        return new FetchResult("https://example.com/in", "https://example.com/out", 200,
                contentType, "UTF-8", body, body.length(), truncatedBytes, List.of());
    }

    private static WebFetchService serviceFor(FetchResult result) {
        return new WebFetchService(new StubFetcher(result), new HtmlToMarkdownExtractor());
    }

    // ------------------------------------------------------------ 分派

    @Test
    void htmlIsRoutedThroughExtractor() {
        String html = "<html><head><title>标题</title></head><body><article><p>"
                + "这是一段足够长的正文内容，用于确保语义容器在打分阶段被稳定选中，从而验证编排层确实把 HTML 交给了提取器处理。"
                + "</p></article></body></html>";

        WebFetchService.Outcome outcome = serviceFor(result("text/html; charset=UTF-8", html, false))
                .fetch("https://example.com/in", 20_000);

        assertTrue(outcome.success());
        assertEquals("标题", outcome.title());
        assertTrue(outcome.markdown().contains("确实把 HTML 交给了提取器"), outcome.markdown());
        assertFalse(outcome.markdown().contains("<p>"), "HTML 标签不应残留在 Markdown 里");
    }

    @Test
    void jsonPassesThroughVerbatim() {
        String json = "{\n  \"name\": \"agentcli\",\n  \"nested\": {\"k\": 1}\n}";

        WebFetchService.Outcome outcome = serviceFor(result("application/json", json, false))
                .fetch("https://example.com/api", 20_000);

        assertTrue(outcome.success());
        assertEquals(json, outcome.markdown(), "JSON 必须原样直通，缩进结构不得被破坏");
    }

    @Test
    void plainTextPassesThrough() {
        WebFetchService.Outcome outcome = serviceFor(result("text/plain; charset=UTF-8", "  纯文本行一\n行二  ", false))
                .fetch("https://example.com/t.txt", 20_000);

        assertTrue(outcome.success());
        assertEquals("纯文本行一\n行二", outcome.markdown());
    }

    @Test
    void unsupportedMimeIsRejectedAtServiceLayer() {
        WebFetchService.Outcome outcome = serviceFor(result("application/zip", "PKbinary", false))
                .fetch("https://example.com/a.zip", 20_000);

        assertFalse(outcome.success());
        assertTrue(outcome.errorMessage().contains("不支持的内容类型"), outcome.errorMessage());
        assertTrue(outcome.errorMessage().contains("application/zip"), outcome.errorMessage());
    }

    // ------------------------------------------------------------ 双截断语义

    @Test
    void charTruncationSetsOnlyCharFlag() {
        String body = "字".repeat(500);

        WebFetchService.Outcome outcome = serviceFor(result("text/plain", body, false))
                .fetch("https://example.com/t", 100);

        assertTrue(outcome.truncatedByChars(), "超出字符预算应置字符截断标志");
        assertFalse(outcome.truncatedByBytes(), "未触发字节上限时不得误报字节截断");
        assertEquals(500, outcome.originalChars(), "originalChars 必须是截断前的长度");
        assertEquals(100, outcome.markdown().length());
        assertTrue(outcome.truncated());
    }

    @Test
    void byteTruncationSetsOnlyByteFlag() {
        WebFetchService.Outcome outcome = serviceFor(result("text/plain", "短内容", true))
                .fetch("https://example.com/t", 20_000);

        assertTrue(outcome.truncatedByBytes(), "传输层字节截断必须原样透传");
        assertFalse(outcome.truncatedByChars(), "内容未超字符预算时不得置字符截断标志");
        assertTrue(outcome.truncated());
    }

    @Test
    void bothTruncationFlagsCanCoexist() {
        WebFetchService.Outcome outcome = serviceFor(result("text/plain", "字".repeat(300), true))
                .fetch("https://example.com/t", 50);

        assertTrue(outcome.truncatedByBytes());
        assertTrue(outcome.truncatedByChars());
    }

    @Test
    void nonPositiveBudgetFallsBackToDefault() {
        WebFetchService.Outcome outcome = serviceFor(result("text/plain", "x".repeat(25_000), false))
                .fetch("https://example.com/t", 0);

        assertEquals(WebFetchService.DEFAULT_MAX_CHARS, outcome.markdown().length());
        assertTrue(outcome.truncatedByChars());
    }

    @Test
    void surrogatePairIsNotSplitByBudget() {
        // 每个 emoji 占两个 char，预算为奇数时必须少留一个而不是劈开代理对
        String body = "😀".repeat(10);

        WebFetchService.Outcome outcome = serviceFor(result("text/plain", body, false))
                .fetch("https://example.com/t", 5);

        assertEquals(4, outcome.markdown().length(), "应回退一个 char 以保全代理对");
        assertFalse(Character.isHighSurrogate(outcome.markdown().charAt(outcome.markdown().length() - 1)));
    }

    // ------------------------------------------------------------ 失败与兜底

    @Test
    void fetchFailureBecomesFailOutcomeWithoutThrowing() {
        WebFetchService service = new WebFetchService(
                new StubFetcher(new IOException("网络访问被拒绝: 禁止访问私有地址（10.0.0.1）")),
                new HtmlToMarkdownExtractor());

        WebFetchService.Outcome outcome = service.fetch("http://10.0.0.1/", 20_000);

        assertFalse(outcome.success());
        assertEquals("网络访问被拒绝: 禁止访问私有地址（10.0.0.1）", outcome.errorMessage());
    }

    @Test
    void blankUrlIsRejectedBeforeFetching() {
        WebFetchService.Outcome outcome = serviceFor(result("text/plain", "x", false)).fetch("  ", 20_000);

        assertFalse(outcome.success());
        assertEquals("url 不能为空", outcome.errorMessage());
    }

    @Test
    void missingTitleFallsBackToHost() {
        WebFetchService.Outcome outcome = serviceFor(result("text/plain", "没有标题的纯文本", false))
                .fetch("https://Example.COM/t.txt", 20_000);

        assertTrue(outcome.success());
        assertEquals("example.com", outcome.title(), "无标题时应退化为域名，让模型知道内容出处");
    }
}
