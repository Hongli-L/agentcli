package com.agentcli.tool.builtin;

import com.agentcli.web.WebFetchService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具外壳用例。
 *
 * <p>通过包级可见构造器注入桩件服务，<b>不起任何 HTTP</b>：
 * 这里要验证的只有参数钳制与排版，抓取与提取的正确性由各自的用例负责。
 */
class WebFetchToolTest {

    /** 记录工具实际传给服务层的字符预算，用于验证钳制。 */
    private final int[] capturedMaxChars = {-1};

    private WebFetchTool toolReturning(WebFetchService.Outcome outcome) {
        WebFetchService stub = new WebFetchService() {
            @Override
            public Outcome fetch(String url, int maxChars) {
                capturedMaxChars[0] = maxChars;
                return outcome;
            }
        };
        return new WebFetchTool(stub);
    }

    @Test
    void blankUrlReturnsSingleLineError() {
        WebFetchTool tool = toolReturning(WebFetchService.Outcome.fail("不应被调用"));

        assertEquals("❌ 抓取失败: url 不能为空", tool.execute(Map.of()));
        assertEquals("❌ 抓取失败: url 不能为空", tool.execute(Map.of("url", "   ")));
        assertEquals(-1, capturedMaxChars[0], "参数非法时不应触达服务层");
    }

    @Test
    void rendersTitleUrlAndMarkdown() {
        WebFetchTool tool = toolReturning(WebFetchService.Outcome.ok(
                "https://example.com/post", "示例标题", "# 正文\n\n段落内容", 120,
                false, false, 0));

        String output = tool.execute(Map.of("url", "https://example.com/post"));

        assertTrue(output.startsWith("🌐 示例标题"), output);
        assertTrue(output.contains("🔗 https://example.com/post"), output);
        assertTrue(output.contains("段落内容"), output);
        assertFalse(output.contains("⚠️"), output);
        assertFalse(output.contains("重定向"), output);
    }

    @Test
    void showsRedirectHintAndTruncationNotice() {
        WebFetchTool tool = toolReturning(WebFetchService.Outcome.ok(
                "https://example.com/final", "标题", "正文", 45_000,
                true, true, 2));

        String output = tool.execute(Map.of("url", "https://example.com/start", "max_chars", "20000"));

        assertTrue(output.contains("（经 2 跳重定向）"), output);
        assertTrue(output.contains("⚠️ 内容已截断（原始 45000 字符，返回前 20000 字符）"), output);
        assertTrue(output.contains("响应体超过体积上限"), output);
    }

    @Test
    void failureFromServiceBecomesSingleLineError() {
        WebFetchTool tool = toolReturning(WebFetchService.Outcome.fail(
                "网络访问被拒绝: 禁止访问链路本地地址（169.254.169.254，云元数据服务常驻此段）"));

        String output = tool.execute(Map.of("url", "http://169.254.169.254/"));

        assertTrue(output.startsWith("❌ 抓取失败: 网络访问被拒绝"), output);
        assertFalse(output.contains("\n"), "失败信息应压成单行: " + output);
    }

    @Test
    void clampsMaxCharsIntoAllowedRange() {
        WebFetchTool tool = toolReturning(WebFetchService.Outcome.ok(
                "https://example.com/", "t", "m", 1, false, false, 0));

        tool.execute(Map.of("url", "https://example.com/", "max_chars", "999999"));
        assertEquals(60_000, capturedMaxChars[0]);

        tool.execute(Map.of("url", "https://example.com/", "max_chars", "1"));
        assertEquals(1_000, capturedMaxChars[0]);

        tool.execute(Map.of("url", "https://example.com/", "max_chars", "不是数字"));
        assertEquals(20_000, capturedMaxChars[0], "非法数字应回落到默认值");

        tool.execute(Map.of("url", "https://example.com/"));
        assertEquals(20_000, capturedMaxChars[0]);
    }

    @Test
    void emptyMarkdownIsReportedExplicitly() {
        WebFetchTool tool = toolReturning(WebFetchService.Outcome.ok(
                "https://example.com/", "空页", "   ", 0, false, false, 0));

        assertTrue(tool.execute(Map.of("url", "https://example.com/")).contains("（未提取到正文内容）"));
    }

    @Test
    void schemaDeclaresUrlAndMaxChars() {
        WebFetchTool tool = new WebFetchTool();
        String schema = tool.parameters().toString();

        assertEquals("web_fetch", tool.name());
        assertTrue(schema.contains("url"), schema);
        assertTrue(schema.contains("max_chars"), schema);
        assertTrue(schema.contains("required"), schema);
        assertTrue(tool.description().contains("Markdown"), tool.description());
    }
}
