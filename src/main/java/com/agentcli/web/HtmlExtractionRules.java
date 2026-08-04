package com.agentcli.web;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTML 正文抽取的<b>纯静态规则表</b>：噪声标签集、打分公式、实体解码、Markdown 前缀映射。
 *
 * <p>把规则从遍历算法里抽出来单独成类，是为了让「调参」和「改算法」互不干扰：
 * 想调整噪声关键词或打分权重时，改的是一张表而不是一段控制流；
 * 反过来改渲染算法时，也不用担心顺手改坏了判定规则。
 *
 * <p>本类是<b>依赖树的叶子</b>：全部方法都是无状态纯函数，不依赖本项目任何其它类、不做 IO。
 */
public final class HtmlExtractionRules {

    /**
     * 结构性噪声标签：内容对「读正文」没有价值，整段丢弃。
     *
     * <p>{@code head} / {@code title} 也在列——标题已在提取的第一步单独取走，
     * 再让它们的文本混进正文只会污染开头。
     */
    private static final Set<String> NOISE_TAGS = Set.of(
            "script", "style", "noscript", "iframe", "nav", "aside", "header", "footer",
            "form", "svg", "canvas", "button", "select", "input", "textarea", "template",
            "object", "embed", "map", "audio", "video", "dialog", "head", "title");

    /** class / id 里出现这些词，基本可以断定是广告位、侧栏或社交组件。 */
    private static final Set<String> NOISE_CLASS_KEYWORDS = Set.of(
            "ads", "advert", "banner", "popup", "modal", "subscribe", "newsletter",
            "related", "recommend", "comment", "share", "social", "breadcrumb",
            "sidebar", "promo", "cookie", "navigation", "navbar", "footer", "header",
            "toolbar", "pagination", "widget", "sponsor");

    /** void 标签：没有结束标签，遇到即视为自闭合。 */
    private static final Set<String> VOID_TAGS = Set.of(
            "br", "img", "hr", "meta", "link", "input", "source", "area",
            "base", "col", "embed", "param", "track", "wbr");

    /** RAWTEXT 标签：内部的 {@code <} 一律当文本，不参与标签切分。 */
    private static final Set<String> RAW_TEXT_TAGS = Set.of("script", "style");

    /** 块级标签：正文区间打分只在这些容器上进行。 */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "article", "main", "section", "div", "body", "p", "blockquote",
            "ul", "ol", "li", "table", "tbody", "tr", "pre", "dl", "dd", "dt",
            "h1", "h2", "h3", "h4", "h5", "h6", "figure", "figcaption", "details");

    /** 语义正文容器：命中即优先，不必再打分比较。 */
    private static final Set<String> SEMANTIC_MAIN_TAGS = Set.of("article", "main");

    /** 打分时认定「这是一段正文」的最小文本长度。 */
    public static final int MIN_MAIN_TEXT_LENGTH = 80;

    /** 高频命名实体表；数字实体走 {@link #decodeEntities} 内的数值分支。 */
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("ensp", " "),
            Map.entry("emsp", " "),
            Map.entry("thinsp", " "),
            Map.entry("copy", "©"),
            Map.entry("reg", "®"),
            Map.entry("trade", "™"),
            Map.entry("hellip", "…"),
            Map.entry("mdash", "—"),
            Map.entry("ndash", "–"),
            Map.entry("lsquo", "‘"),
            Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"),
            Map.entry("laquo", "«"),
            Map.entry("raquo", "»"),
            Map.entry("middot", "·"),
            Map.entry("bull", "•"),
            Map.entry("times", "×"),
            Map.entry("divide", "÷"),
            Map.entry("deg", "°"),
            Map.entry("plusmn", "±"),
            Map.entry("frac12", "½"),
            Map.entry("sup2", "²"),
            Map.entry("sup3", "³"),
            Map.entry("micro", "µ"),
            Map.entry("para", "¶"),
            Map.entry("sect", "§"),
            Map.entry("dagger", "†"),
            Map.entry("permil", "‰"),
            Map.entry("prime", "′"),
            Map.entry("euro", "€"),
            Map.entry("pound", "£"),
            Map.entry("yen", "¥"),
            Map.entry("cent", "¢"),
            Map.entry("larr", "←"),
            Map.entry("rarr", "→"),
            Map.entry("harr", "↔"),
            Map.entry("darr", "↓"),
            Map.entry("uarr", "↑"));

    /** 实体最长长度（含 {@code &} 与 {@code ;}），超过就不当实体看，避免全文回扫。 */
    private static final int MAX_ENTITY_LENGTH = 12;

    private HtmlExtractionRules() {
    }

    /** 是否是结构性噪声标签。 */
    public static boolean isNoiseTag(String tag) {
        return tag != null && NOISE_TAGS.contains(tag.toLowerCase(Locale.ROOT));
    }

    /**
     * class / id 文本是否命中噪声关键词。
     *
     * @param classAndId 把 class 与 id 拼在一起的字符串
     */
    public static boolean isNoiseMarker(String classAndId) {
        if (classAndId == null || classAndId.isBlank()) {
            return false;
        }
        String lower = classAndId.toLowerCase(Locale.ROOT);
        for (String keyword : NOISE_CLASS_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 是否是无结束标签的 void 标签。 */
    public static boolean isVoidTag(String tag) {
        return tag != null && VOID_TAGS.contains(tag.toLowerCase(Locale.ROOT));
    }

    /** 是否是 RAWTEXT 标签（内部不再切分标签）。 */
    public static boolean isRawTextTag(String tag) {
        return tag != null && RAW_TEXT_TAGS.contains(tag.toLowerCase(Locale.ROOT));
    }

    /** 是否是参与正文打分的块级容器。 */
    public static boolean isBlockTag(String tag) {
        return tag != null && BLOCK_TAGS.contains(tag.toLowerCase(Locale.ROOT));
    }

    /** 是否是语义正文容器（{@code article} / {@code main}）。 */
    public static boolean isSemanticMainTag(String tag) {
        return tag != null && SEMANTIC_MAIN_TAGS.contains(tag.toLowerCase(Locale.ROOT));
    }

    /** {@code h1}-{@code h6} 到 Markdown 井号前缀；非标题返回空串。 */
    public static String headingPrefix(String tag) {
        if (tag == null || tag.length() != 2 || Character.toLowerCase(tag.charAt(0)) != 'h') {
            return "";
        }
        char level = tag.charAt(1);
        if (level < '1' || level > '6') {
            return "";
        }
        return "#".repeat(level - '0') + " ";
    }

    /**
     * 正文容器打分。
     *
     * <p>公式 {@code textLen × (1 − min(linkRatio × 2, 1))}。链接密度是区分「正文」与
     * 「导航 / 列表页」最省事也最有效的信号：导航块文字不少，但几乎每个字都在 {@code <a>} 里，
     * 乘以 2 之后权重迅速归零。文本太短的容器直接判 0 分，避免一个只有标题的小 div 抢到正文位置。
     *
     * @param textLen 容器内纯文本长度
     * @param linkLen 其中处于 {@code <a>} 内的文本长度
     * @return 分数，越大越像正文
     */
    public static double scoreContainer(int textLen, int linkLen) {
        if (textLen < MIN_MAIN_TEXT_LENGTH) {
            return 0.0;
        }
        double linkRatio = textLen == 0 ? 1.0 : (double) linkLen / (double) textLen;
        double penalty = Math.min(linkRatio * 2.0, 1.0);
        return textLen * (1.0 - penalty);
    }

    /**
     * HTML 实体解码（零依赖手写）。
     *
     * <p>这是自研提取器最容易漏掉的一环——通用 HTML 库把它当成免费附赠品，
     * 一旦自己动手就必须补齐，否则正文里会到处散落 {@code &amp;} {@code &#39;}。
     * 命名实体走查表，数字实体走十进制 / 十六进制解析，非法实体原样保留。
     *
     * @param raw 原始文本；null 返回空串
     */
    public static String decodeEntities(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int ampersand = raw.indexOf('&');
        if (ampersand < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int length = raw.length();
        while (i < length) {
            char c = raw.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semicolon = raw.indexOf(';', i + 1);
            if (semicolon < 0 || semicolon - i > MAX_ENTITY_LENGTH) {
                out.append(c);
                i++;
                continue;
            }
            String body = raw.substring(i + 1, semicolon);
            String decoded = decodeOne(body);
            if (decoded == null) {
                out.append(c);
                i++;
                continue;
            }
            out.append(decoded);
            i = semicolon + 1;
        }
        return out.toString();
    }

    /** 解码单个实体主体（不含 {@code &} 与 {@code ;}）；无法识别返回 null。 */
    private static String decodeOne(String body) {
        if (body.isEmpty()) {
            return null;
        }
        if (body.charAt(0) == '#') {
            return decodeNumeric(body);
        }
        String named = NAMED_ENTITIES.get(body);
        if (named != null) {
            return named;
        }
        // 实体名大小写不敏感的常见写法，例如 &AMP;
        return NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
    }

    private static String decodeNumeric(String body) {
        try {
            int codePoint;
            if (body.length() > 2 && (body.charAt(1) == 'x' || body.charAt(1) == 'X')) {
                codePoint = Integer.parseInt(body.substring(2), 16);
            } else if (body.length() > 1) {
                codePoint = Integer.parseInt(body.substring(1), 10);
            } else {
                return null;
            }
            if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT) {
                return null;
            }
            return new String(Character.toChars(codePoint));
        } catch (IllegalArgumentException e) {
            // 覆盖 NumberFormatException（非法数字）与 Character.toChars 的非法码点
            return null;
        }
    }

    /**
     * 压掉多余空行与行尾空白。
     *
     * <p>渲染器为了保证块级元素之间一定有间隔，会比较激进地插入换行；
     * 深嵌套的 {@code div} 更会连插一堆。统一在最后收一次口，比在渲染时到处判断要简单可靠得多。
     */
    public static String collapseBlankLines(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int blankRun = 0;
        for (String line : lines) {
            String trimmed = stripTrailing(line);
            if (trimmed.isEmpty()) {
                blankRun++;
                if (blankRun > 1) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
