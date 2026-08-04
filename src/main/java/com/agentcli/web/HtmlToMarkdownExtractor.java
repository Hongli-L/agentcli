package com.agentcli.web;

import com.agentcli.web.HtmlTagTokenizer.Kind;
import com.agentcli.web.HtmlTagTokenizer.Token;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 零依赖 HTML → Markdown 正文提取器。
 *
 * <p>因为 {@link HtmlTagTokenizer} 不建 DOM 树，这里用<b>两阶段扫描</b>代替树遍历：
 * <ol>
 *     <li><b>Phase 1 {@code locateMainRegion}</b>：走一遍 token 流，用栈记录块级容器的起止索引，
 *         统计每个容器的文本长度与链接文本长度，调
 *         {@link HtmlExtractionRules#scoreContainer(int, int)} 打分，选出正文区间。
 *         语义容器（{@code article} / {@code main} / {@code role=main}）文本够长时直接命中，跳过打分。</li>
 *     <li><b>Phase 2 {@code render}</b>：只渲染该区间，且<b>用显式循环而非递归下降</b>。
 *         这是「畸形 HTML 不崩」的关键——递归遇到一千层嵌套就是
 *         {@code StackOverflowError}，扁平扫描顶多多输出几个换行，最后统一压掉。</li>
 * </ol>
 *
 * <p>对未闭合标签的态度是<b>只保证不崩，不修复 HTML</b>：Phase 1 遇到未闭合容器，
 * 用「下一个同级块的结束位置」或区间末尾兜底；Phase 2 每个标签独立成型，
 * 少一个结束标签只影响局部排版，不会让整篇输出错位。
 */
public class HtmlToMarkdownExtractor {

    /** 容器栈与列表嵌套的深度上限，超出后平坦化处理。 */
    private static final int MAX_DEPTH = 512;

    /** 列表缩进最多这么多层，再深就没有阅读价值了。 */
    private static final int MAX_LIST_INDENT = 8;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final HtmlTagTokenizer tokenizer = new HtmlTagTokenizer();

    /**
     * 抽取正文并转成 Markdown。
     *
     * @param html    原始 HTML
     * @param baseUrl 用于把相对链接转成绝对链接的基准 URL，可为空
     * @return 标题 + Markdown 正文；<b>绝不抛异常</b>
     */
    public Extracted extract(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return new Extracted("", "");
        }
        List<Token> tokens = tokenizer.tokenize(html);
        String title = pickTitle(tokens);
        List<Token> cleaned = dropNoise(tokens);
        Region region = locateMainRegion(cleaned);
        String markdown = HtmlExtractionRules.collapseBlankLines(render(cleaned, region, baseUrl));
        if (markdown.isBlank() && !cleaned.isEmpty()) {
            // 打分选错了区间（例如整页都是短块），退回全文渲染兜底
            markdown = HtmlExtractionRules.collapseBlankLines(
                    render(cleaned, new Region(0, cleaned.size()), baseUrl));
        }
        return new Extracted(title, markdown);
    }

    // ---------------------------------------------------------------- 标题

    /** 标题优先取 {@code <title>}，没有则回退到第一个 {@code <h1>}。 */
    private String pickTitle(List<Token> tokens) {
        String fromTitleTag = collectTagText(tokens, "title");
        if (!fromTitleTag.isBlank()) {
            return fromTitleTag;
        }
        return collectTagText(tokens, "h1");
    }

    private static String collectTagText(List<Token> tokens, String tag) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() != Kind.START_TAG || !token.name().equals(tag)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int j = i + 1; j < tokens.size(); j++) {
                Token inner = tokens.get(j);
                if (inner.kind() == Kind.END_TAG && inner.name().equals(tag)) {
                    break;
                }
                if (inner.kind() == Kind.TEXT) {
                    sb.append(HtmlExtractionRules.decodeEntities(inner.text()));
                }
            }
            String text = WHITESPACE.matcher(sb.toString()).replaceAll(" ").trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 噪声剔除

    /**
     * 丢弃噪声容器及其全部内容。
     *
     * <p>未闭合的噪声容器<b>只丢起始标签本身</b>：找不到配对结束标签时若一路吞到文档末尾，
     * 一个漏写 {@code </div>} 的广告位就能把整篇正文连坐删光，代价远大于收益。
     */
    private List<Token> dropNoise(List<Token> tokens) {
        List<Token> kept = new ArrayList<>(tokens.size());
        int index = 0;
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.kind() == Kind.COMMENT || token.kind() == Kind.DOCTYPE) {
                index++;
                continue;
            }
            if (token.kind() == Kind.SELF_CLOSING && isNoise(token)) {
                index++;
                continue;
            }
            if (token.kind() == Kind.START_TAG && isNoise(token)) {
                int end = findMatchingEnd(tokens, index);
                index = end < 0 ? index + 1 : end + 1;
                continue;
            }
            kept.add(token);
            index++;
        }
        return kept;
    }

    private static boolean isNoise(Token token) {
        if (HtmlExtractionRules.isNoiseTag(token.name())) {
            return true;
        }
        String marker = token.attr("class") + " " + token.attr("id") + " " + token.attr("role");
        return HtmlExtractionRules.isNoiseMarker(marker);
    }

    /** 找同名配对结束标签（带深度计数）；不存在返回 -1。 */
    private static int findMatchingEnd(List<Token> tokens, int startIndex) {
        String name = tokens.get(startIndex).name();
        int depth = 0;
        for (int i = startIndex; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.START_TAG && token.name().equals(name)) {
                depth++;
            } else if (token.kind() == Kind.END_TAG && token.name().equals(name)) {
                depth--;
                if (depth <= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Phase 1

    /**
     * 定位正文区间。
     *
     * <p>先算两个前缀和数组（累计文本长度、累计链接内文本长度），之后任意区间的
     * 文本量与链接密度都能 O(1) 求出，避免对每个候选容器重扫一遍 token 流。
     */
    private Region locateMainRegion(List<Token> tokens) {
        int size = tokens.size();
        if (size == 0) {
            return new Region(0, 0);
        }
        int[] textPrefix = new int[size + 1];
        int[] linkPrefix = new int[size + 1];
        int linkDepth = 0;
        for (int i = 0; i < size; i++) {
            textPrefix[i + 1] = textPrefix[i];
            linkPrefix[i + 1] = linkPrefix[i];
            Token token = tokens.get(i);
            if (token.kind() == Kind.TEXT) {
                int length = HtmlExtractionRules.decodeEntities(token.text()).trim().length();
                textPrefix[i + 1] += length;
                if (linkDepth > 0) {
                    linkPrefix[i + 1] += length;
                }
            } else if (token.kind() == Kind.START_TAG && token.name().equals("a")) {
                linkDepth++;
            } else if (token.kind() == Kind.END_TAG && token.name().equals("a") && linkDepth > 0) {
                linkDepth--;
            }
        }

        Deque<OpenContainer> stack = new ArrayDeque<>();
        Candidate candidate = new Candidate();
        for (int i = 0; i < size; i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.START_TAG && HtmlExtractionRules.isBlockTag(token.name())) {
                if (stack.size() < MAX_DEPTH) {
                    stack.push(new OpenContainer(token.name(), i, isSemanticMain(token)));
                }
                continue;
            }
            if (token.kind() == Kind.END_TAG && HtmlExtractionRules.isBlockTag(token.name())) {
                while (!stack.isEmpty()) {
                    OpenContainer open = stack.pop();
                    evaluate(candidate, open, i, textPrefix, linkPrefix);
                    if (open.name().equals(token.name())) {
                        break;
                    }
                }
            }
        }
        // 未闭合的容器统一用文档末尾兜底
        while (!stack.isEmpty()) {
            evaluate(candidate, stack.pop(), size, textPrefix, linkPrefix);
        }

        if (candidate.semantic != null) {
            return candidate.semantic;
        }
        return candidate.best != null ? candidate.best : new Region(0, size);
    }

    private static void evaluate(Candidate candidate, OpenContainer open, int end,
                                 int[] textPrefix, int[] linkPrefix) {
        int start = open.index() + 1;
        if (end <= start) {
            return;
        }
        int textLength = textPrefix[end] - textPrefix[start];
        int linkLength = linkPrefix[end] - linkPrefix[start];
        if (open.semantic() && textLength >= HtmlExtractionRules.MIN_MAIN_TEXT_LENGTH
                && (candidate.semantic == null || start < candidate.semantic.start())) {
            candidate.semantic = new Region(start, end);
        }
        double score = HtmlExtractionRules.scoreContainer(textLength, linkLength);
        if (score > candidate.bestScore) {
            candidate.bestScore = score;
            candidate.best = new Region(start, end);
        }
    }

    private static boolean isSemanticMain(Token token) {
        return HtmlExtractionRules.isSemanticMainTag(token.name())
                || "main".equalsIgnoreCase(token.attr("role").trim());
    }

    // ---------------------------------------------------------------- Phase 2

    /**
     * 渲染指定区间为 Markdown。
     *
     * <p>每个标签独立成型、不依赖父级上下文，所以整个过程是一条扁平的 for 循环，
     * 没有递归也就没有栈深度问题。多余的换行交给
     * {@link HtmlExtractionRules#collapseBlankLines(String)} 统一收口。
     */
    private String render(List<Token> tokens, Region region, String baseUrl) {
        StringBuilder out = new StringBuilder();
        StringBuilder linkText = new StringBuilder();
        Deque<int[]> lists = new ArrayDeque<>();
        String linkHref = "";
        boolean inLink = false;
        boolean inPre = false;
        int rowCells = 0;
        boolean headerRow = false;

        int start = Math.max(0, region.start());
        int end = Math.min(tokens.size(), region.end());

        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);

            if (token.kind() == Kind.COMMENT || token.kind() == Kind.DOCTYPE) {
                continue;
            }

            if (token.kind() == Kind.TEXT) {
                appendText(out, linkText, inLink, inPre, token.text());
                continue;
            }

            String name = token.name();
            String heading = HtmlExtractionRules.headingPrefix(name);

            if (token.opens()) {
                if (!heading.isEmpty()) {
                    ensureBlankLine(out);
                    out.append(heading);
                    continue;
                }
                switch (name) {
                    case "br" -> {
                        if (inLink) {
                            linkText.append(' ');
                        } else {
                            out.append('\n');
                        }
                    }
                    case "hr" -> {
                        ensureBlankLine(out);
                        out.append("---");
                        ensureBlankLine(out);
                    }
                    case "p", "div", "section", "article", "main", "figure", "figcaption",
                         "dl", "dt", "dd", "details", "summary", "address" -> ensureBlankLine(out);
                    case "blockquote" -> {
                        ensureBlankLine(out);
                        out.append("> ");
                    }
                    case "ul" -> {
                        pushList(lists, 0);
                        ensureNewline(out);
                    }
                    case "ol" -> {
                        pushList(lists, 1);
                        ensureNewline(out);
                    }
                    case "li" -> {
                        ensureNewline(out);
                        int indent = Math.min(Math.max(0, lists.size() - 1), MAX_LIST_INDENT);
                        out.append("  ".repeat(indent));
                        int[] top = lists.peek();
                        if (top != null && top[0] == 1) {
                            top[1]++;
                            out.append(top[1]).append(". ");
                        } else {
                            out.append("- ");
                        }
                    }
                    case "pre" -> {
                        inPre = true;
                        ensureBlankLine(out);
                        out.append("```\n");
                    }
                    case "code" -> {
                        if (!inPre) {
                            appendInline(out, linkText, inLink, "`");
                        }
                    }
                    case "strong", "b" -> appendInline(out, linkText, inLink, "**");
                    case "em", "i" -> appendInline(out, linkText, inLink, "*");
                    case "a" -> {
                        if (inLink) {
                            flushLink(out, linkText, linkHref, baseUrl);
                        }
                        inLink = true;
                        linkText.setLength(0);
                        linkHref = token.attr("href");
                    }
                    case "img" -> {
                        String src = absolutize(baseUrl, token.attr("src"));
                        if (!src.isEmpty()) {
                            String alt = token.attr("alt").isBlank() ? "image" : token.attr("alt").trim();
                            appendInline(out, linkText, inLink, "![" + alt + "](" + src + ")");
                        }
                    }
                    case "table" -> ensureBlankLine(out);
                    case "tr" -> {
                        ensureNewline(out);
                        out.append('|');
                        rowCells = 0;
                        headerRow = false;
                    }
                    case "td" -> {
                        out.append(' ');
                        rowCells++;
                    }
                    case "th" -> {
                        out.append(' ');
                        rowCells++;
                        headerRow = true;
                    }
                    default -> {
                        // 白名单之外的标签只剥壳，内容照常保留
                    }
                }
                continue;
            }

            // 结束标签
            if (!heading.isEmpty()) {
                ensureBlankLine(out);
                continue;
            }
            switch (name) {
                case "p", "div", "section", "article", "main", "blockquote", "figure",
                     "figcaption", "dl", "dt", "dd", "details", "summary", "address" -> ensureBlankLine(out);
                case "ul", "ol" -> {
                    if (!lists.isEmpty()) {
                        lists.pop();
                    }
                    if (lists.isEmpty()) {
                        ensureBlankLine(out);
                    } else {
                        ensureNewline(out);
                    }
                }
                case "li" -> ensureNewline(out);
                case "pre" -> {
                    inPre = false;
                    ensureNewline(out);
                    out.append("```");
                    ensureBlankLine(out);
                }
                case "code" -> {
                    if (!inPre) {
                        appendInline(out, linkText, inLink, "`");
                    }
                }
                case "strong", "b" -> appendInline(out, linkText, inLink, "**");
                case "em", "i" -> appendInline(out, linkText, inLink, "*");
                case "a" -> {
                    if (inLink) {
                        flushLink(out, linkText, linkHref, baseUrl);
                        inLink = false;
                        linkHref = "";
                    }
                }
                case "td", "th" -> out.append(" |");
                case "tr" -> {
                    out.append('\n');
                    if (headerRow && rowCells > 0) {
                        out.append('|').append(" --- |".repeat(rowCells)).append('\n');
                    }
                }
                case "table" -> ensureBlankLine(out);
                default -> {
                    // 其它结束标签不产生排版
                }
            }
        }

        if (inLink) {
            flushLink(out, linkText, linkHref, baseUrl);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 渲染原语

    private static void pushList(Deque<int[]> lists, int ordered) {
        if (lists.size() < MAX_DEPTH) {
            lists.push(new int[]{ordered, 0});
        }
    }

    private static void appendText(StringBuilder out, StringBuilder linkText,
                                   boolean inLink, boolean inPre, String raw) {
        String decoded = HtmlExtractionRules.decodeEntities(raw);
        if (inPre) {
            if (inLink) {
                linkText.append(decoded);
            } else {
                out.append(decoded);
            }
            return;
        }
        String collapsed = WHITESPACE.matcher(decoded).replaceAll(" ");
        if (collapsed.isBlank()) {
            if (inLink) {
                if (linkText.length() > 0) {
                    linkText.append(' ');
                }
            } else if (endsWithVisible(out)) {
                out.append(' ');
            }
            return;
        }
        if (inLink) {
            linkText.append(collapsed);
            return;
        }
        if (!endsWithVisible(out)) {
            collapsed = collapsed.startsWith(" ") ? collapsed.substring(1) : collapsed;
        }
        out.append(collapsed);
    }

    private static boolean endsWithVisible(StringBuilder sb) {
        if (sb.length() == 0) {
            return false;
        }
        char last = sb.charAt(sb.length() - 1);
        return last != '\n' && last != ' ';
    }

    private static void appendInline(StringBuilder out, StringBuilder linkText,
                                     boolean inLink, String marker) {
        if (inLink) {
            linkText.append(marker);
        } else {
            out.append(marker);
        }
    }

    private static void flushLink(StringBuilder out, StringBuilder linkText,
                                  String href, String baseUrl) {
        String text = linkText.toString().trim();
        linkText.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String url = absolutize(baseUrl, href);
        if (url.isEmpty()) {
            out.append(text);
        } else {
            out.append('[').append(text).append("](").append(url).append(')');
        }
    }

    private static void ensureNewline(StringBuilder sb) {
        if (sb.length() == 0) {
            return;
        }
        if (sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void ensureBlankLine(StringBuilder sb) {
        if (sb.length() == 0) {
            return;
        }
        ensureNewline(sb);
        if (sb.length() >= 2 && sb.charAt(sb.length() - 2) == '\n') {
            return;
        }
        sb.append('\n');
    }

    /**
     * 相对链接转绝对链接。
     *
     * <p>顺手过滤 {@code javascript:} / {@code data:} 这类伪协议——它们对阅读正文毫无价值，
     * 塞进 Markdown 只会浪费上下文。
     */
    private static String absolutize(String baseUrl, String href) {
        if (href == null) {
            return "";
        }
        String trimmed = href.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
            return "";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return trimmed;
        }
        try {
            return URI.create(baseUrl.trim()).resolve(trimmed).toString();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    // ---------------------------------------------------------------- 嵌套类型

    /** 正文区间（半开区间 {@code [start, end)}，索引针对剔噪后的 token 列表）。 */
    public record Region(int start, int end) {
    }

    /** 抽取产物。 */
    public record Extracted(String title, String markdown) {

        public Extracted {
            title = title == null ? "" : title;
            markdown = markdown == null ? "" : markdown;
        }
    }

    /** Phase 1 打分过程中栈里的一个未闭合容器。 */
    private record OpenContainer(String name, int index, boolean semantic) {
    }

    /** Phase 1 的可变累计结果（局部使用，不对外暴露）。 */
    private static final class Candidate {
        private Region best;
        private double bestScore;
        private Region semantic;
    }
}
