package com.agentcli.web;

import com.agentcli.web.HtmlToMarkdownExtractor.Extracted;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 正文抽取与 Markdown 渲染用例：纯字符串进、纯字符串出。 */
class HtmlToMarkdownExtractorTest {

    private final HtmlToMarkdownExtractor extractor = new HtmlToMarkdownExtractor();

    /** 正文长度需稳定超过语义容器的最小文本阈值（{@code MIN_MAIN_TEXT_LENGTH=80}），才能命中 article/main 分支。 */
    private static final String FILLER =
            "这是一段足够长的正文内容，用来确保语义容器在打分阶段被正常选中，而不是被某个更短的兄弟节点抢走。"
            + "为了让正文长度稳定超过最小阈值，这里再补充一段内容：智能体在抓取网页之后必须把噪声区块剔除，"
            + "只保留真正有价值的段落供模型阅读，因此正文抽取器在定位主区域时会优先采纳语义化标签。";

    @Test
    void extractsTitleFromTitleTag() {
        Extracted result = extractor.extract(
                "<html><head><title>页面标题</title></head><body><article><p>" + FILLER + "</p></article></body></html>",
                "https://example.com/");

        assertEquals("页面标题", result.title());
    }

    @Test
    void fallsBackToH1WhenTitleTagMissing() {
        Extracted result = extractor.extract(
                "<html><body><article><h1>主标题</h1><p>" + FILLER + "</p></article></body></html>",
                "https://example.com/");

        assertEquals("主标题", result.title());
    }

    @Test
    void dropsNoiseContainers() {
        String html = "<html><head><title>T</title></head><body>"
                + "<nav>NAVLINKS</nav>"
                + "<div class=\"advert\">ADTEXT</div>"
                + "<script>var TRACKER = 1;</script>"
                + "<article><p>" + FILLER + "</p></article>"
                + "<footer>FOOTERTEXT</footer>"
                + "</body></html>";

        Extracted result = extractor.extract(html, "https://example.com/");

        assertFalse(result.markdown().contains("NAVLINKS"), result.markdown());
        assertFalse(result.markdown().contains("ADTEXT"), result.markdown());
        assertFalse(result.markdown().contains("TRACKER"), result.markdown());
        assertFalse(result.markdown().contains("FOOTERTEXT"), result.markdown());
        assertTrue(result.markdown().contains("语义容器"), result.markdown());
    }

    @Test
    void rendersHeadingsWithHashPrefix() {
        Extracted result = extractor.extract(
                "<article><h2>小节标题</h2><p>" + FILLER + "</p><h3>更小的标题</h3></article>",
                null);

        assertTrue(result.markdown().contains("## 小节标题"), result.markdown());
        assertTrue(result.markdown().contains("### 更小的标题"), result.markdown());
    }

    @Test
    void rendersUnorderedAndOrderedLists() {
        Extracted result = extractor.extract(
                "<article><p>" + FILLER + "</p>"
                        + "<ul><li>alpha</li><li>beta</li></ul>"
                        + "<ol><li>first</li><li>second</li></ol></article>",
                null);

        String markdown = result.markdown();
        assertTrue(markdown.contains("- alpha"), markdown);
        assertTrue(markdown.contains("- beta"), markdown);
        assertTrue(markdown.contains("1. first"), markdown);
        assertTrue(markdown.contains("2. second"), markdown);
    }

    @Test
    void resolvesRelativeLinksAgainstBaseUrl() {
        Extracted result = extractor.extract(
                "<article><p>" + FILLER + " <a href=\"/docs/guide\">指南</a></p></article>",
                "https://example.com/blog/post");

        assertTrue(result.markdown().contains("[指南](https://example.com/docs/guide)"), result.markdown());
    }

    @Test
    void dropsPseudoProtocolLinksButKeepsText() {
        Extracted result = extractor.extract(
                "<article><p>" + FILLER + " <a href=\"javascript:void(0)\">点我</a></p></article>",
                "https://example.com/");

        assertTrue(result.markdown().contains("点我"), result.markdown());
        assertFalse(result.markdown().contains("javascript:"), result.markdown());
    }

    @Test
    void rendersPreAsFencedCodeBlock() {
        Extracted result = extractor.extract(
                "<article><p>" + FILLER + "</p><pre><code>int x = 1;</code></pre></article>",
                null);

        assertTrue(result.markdown().contains("```"), result.markdown());
        assertTrue(result.markdown().contains("int x = 1;"), result.markdown());
    }

    @Test
    void decodesNamedAndNumericEntities() {
        Extracted result = extractor.extract(
                "<article><p>" + FILLER + " A&amp;B &lt;tag&gt; &#65; &#x42; &hellip;</p></article>",
                null);

        String markdown = result.markdown();
        assertTrue(markdown.contains("A&B"), markdown);
        assertTrue(markdown.contains("<tag>"), markdown);
        assertTrue(markdown.contains("A B"), markdown);
        assertTrue(markdown.contains("…"), markdown);
    }

    @Test
    void rendersInlineEmphasisAndImages() {
        Extracted result = extractor.extract(
                "<article><p>" + FILLER + " <strong>重点</strong> 与 <em>强调</em>"
                        + " <img src=\"/i.png\" alt=\"图\"></p></article>",
                "https://example.com/");

        String markdown = result.markdown();
        assertTrue(markdown.contains("**重点**"), markdown);
        assertTrue(markdown.contains("*强调*"), markdown);
        assertTrue(markdown.contains("![图](https://example.com/i.png)"), markdown);
    }

    @Test
    void prefersSemanticMainOverShorterSiblings() {
        String html = "<body>"
                + "<div><p>短小的旁支内容</p></div>"
                + "<main><p>" + FILLER + "这是真正的正文。</p></main>"
                + "</body>";

        Extracted result = extractor.extract(html, null);

        assertTrue(result.markdown().contains("这是真正的正文"), result.markdown());
        assertFalse(result.markdown().contains("短小的旁支内容"), result.markdown());
    }

    @Test
    void emptyOrBlankHtmlYieldsEmptyResult() {
        assertEquals("", extractor.extract(null, null).title());
        assertEquals("", extractor.extract(null, null).markdown());
        assertEquals("", extractor.extract("   ", "https://example.com/").markdown());
    }

    @Test
    void deeplyNestedAndUnclosedHtmlDoesNotThrow() {
        StringBuilder deep = new StringBuilder("<body>");
        for (int i = 0; i < 3000; i++) {
            deep.append("<div>");
        }
        deep.append("最深处的正文");
        // 刻意不闭合任何一层

        Extracted result = assertDoesNotThrow(() -> extractor.extract(deep.toString(), null));
        assertTrue(result.markdown().contains("最深处的正文"), result.markdown());
    }

    @Test
    void unclosedNoiseContainerDoesNotSwallowWholeDocument() {
        // 漏写 </div> 的广告位不该把后面的正文一起带走
        String html = "<body><div class=\"banner\">ADTEXT<article><p>" + FILLER + "</p></article></body>";

        Extracted result = extractor.extract(html, null);

        assertTrue(result.markdown().contains("语义容器"), result.markdown());
    }
}
