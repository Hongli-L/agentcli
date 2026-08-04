package com.agentcli.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 手写 HTML 状态机扫描器：把一段 HTML 单遍切分成扁平的 {@link Token} 序列。
 *
 * <p><b>为什么不能用正则切标签</b>：HTML 有三个特性天生带状态，正则表达不了——
 * ① {@code <script>} 内部的 {@code <} 不是标签起始；
 * ② 属性值引号里可以出现 {@code >}；
 * ③ 注释 {@code <!-- <div> -->} 里的东西不算标签。
 * 所以这里的分工是：<b>状态机负责「切分」（有状态、全局），正则只负责「清洗」（无状态、局部）</b>。
 *
 * <p><b>RAWTEXT 模式是关键</b>：进入 {@code <script>} / {@code <style>} 后只找匹配的结束标签，
 * 中间所有 {@code <} 一律当普通文本。这正是纯正则方案必然出错的地方。
 *
 * <p><b>不建 DOM 树</b>：省掉了通用 HTML 库最重的那部分，也是零依赖可行的前提。
 * 代价是上层要用两阶段扫描代替树遍历，收益是内存占用可控、畸形输入不会把结构撑爆。
 *
 * <p><b>防爆保护</b>：token 数上限 {@value #MAX_TOKENS}，超限直接停止扫描。
 * 面对「十万个未闭合 div」这类畸形页面时，宁可少解析也不能拖垮进程。
 */
public class HtmlTagTokenizer {

    /** token 数量上限，防止畸形 HTML 撑爆内存。 */
    private static final int MAX_TOKENS = 200_000;

    /**
     * 切分 HTML。
     *
     * @param html 原始 HTML；null 或空串返回空列表
     * @return 扁平 token 序列；<b>绝不抛异常</b>
     */
    public List<Token> tokenize(String html) {
        List<Token> tokens = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return tokens;
        }
        Cursor cursor = new Cursor(html.toCharArray());
        StringBuilder text = new StringBuilder();

        while (cursor.hasNext() && tokens.size() < MAX_TOKENS) {
            char c = cursor.peek();
            if (c != '<') {
                text.append(c);
                cursor.advance(1);
                continue;
            }

            // <!-- 注释 -->
            if (cursor.startsWith("<!--")) {
                flushText(tokens, text);
                cursor.advance(4);
                int end = cursor.indexOf("-->", cursor.position());
                int stop = end < 0 ? cursor.length() : end;
                tokens.add(Token.comment(cursor.slice(cursor.position(), stop)));
                cursor.moveTo(end < 0 ? cursor.length() : end + 3);
                continue;
            }

            // <!DOCTYPE ...> 或 <?xml ...?>
            if (cursor.startsWith("<!") || cursor.startsWith("<?")) {
                flushText(tokens, text);
                int end = cursor.indexOfChar('>', cursor.position() + 2);
                int stop = end < 0 ? cursor.length() : end;
                tokens.add(Token.doctype(cursor.slice(cursor.position(), stop)));
                cursor.moveTo(end < 0 ? cursor.length() : end + 1);
                continue;
            }

            // </tag>
            if (cursor.startsWith("</")) {
                if (!isNameStart(cursor.charAt(cursor.position() + 2))) {
                    text.append(c);
                    cursor.advance(1);
                    continue;
                }
                flushText(tokens, text);
                cursor.advance(2);
                String name = readTagName(cursor);
                skipToTagEnd(cursor);
                tokens.add(Token.end(name));
                continue;
            }

            // <tag ...>
            if (!isNameStart(cursor.charAt(cursor.position() + 1))) {
                // 落单的 '<'（例如数学表达式 a < b）当作普通文本
                text.append(c);
                cursor.advance(1);
                continue;
            }
            flushText(tokens, text);
            cursor.advance(1);
            String name = readTagName(cursor);
            Map<String, String> attributes = readAttributes(cursor);
            boolean selfClosing = cursor.lastTagSelfClosing || HtmlExtractionRules.isVoidTag(name);
            tokens.add(selfClosing ? Token.selfClosing(name, attributes) : Token.start(name, attributes));

            if (!selfClosing && HtmlExtractionRules.isRawTextTag(name)) {
                String raw = readRawText(cursor, name);
                if (!raw.isEmpty()) {
                    tokens.add(Token.text(raw));
                }
                tokens.add(Token.end(name));
            }
        }

        flushText(tokens, text);
        return tokens;
    }

    // ---------------------------------------------------------------- 扫描原语

    /** 读取标签名并统一小写（HTML 标签名大小写不敏感）。 */
    private static String readTagName(Cursor cursor) {
        StringBuilder sb = new StringBuilder();
        while (cursor.hasNext() && isNameChar(cursor.peek())) {
            sb.append(cursor.peek());
            cursor.advance(1);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 读取属性列表，消费到标签结束的 {@code >} 之后。
     *
     * <p>属性值的三种写法（双引号 / 单引号 / 无引号）分别处理：只有区分了引号状态，
     * 引号内部的 {@code >} 才不会被误判成标签结束。
     */
    private static Map<String, String> readAttributes(Cursor cursor) {
        Map<String, String> attributes = new LinkedHashMap<>();
        cursor.lastTagSelfClosing = false;
        while (cursor.hasNext()) {
            cursor.skipWhitespace();
            if (!cursor.hasNext()) {
                break;
            }
            char c = cursor.peek();
            if (c == '>') {
                cursor.advance(1);
                break;
            }
            if (c == '/') {
                if (cursor.charAt(cursor.position() + 1) == '>') {
                    cursor.lastTagSelfClosing = true;
                    cursor.advance(2);
                    break;
                }
                cursor.advance(1);
                continue;
            }

            StringBuilder nameSb = new StringBuilder();
            while (cursor.hasNext()) {
                char n = cursor.peek();
                if (isWhitespace(n) || n == '=' || n == '>' || n == '/') {
                    break;
                }
                nameSb.append(n);
                cursor.advance(1);
            }
            if (nameSb.length() == 0) {
                cursor.advance(1);
                continue;
            }

            cursor.skipWhitespace();
            String value = "";
            if (cursor.hasNext() && cursor.peek() == '=') {
                cursor.advance(1);
                cursor.skipWhitespace();
                value = readAttributeValue(cursor);
            }
            attributes.put(nameSb.toString().toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }

    private static String readAttributeValue(Cursor cursor) {
        if (!cursor.hasNext()) {
            return "";
        }
        char quote = cursor.peek();
        if (quote == '"' || quote == '\'') {
            cursor.advance(1);
            int start = cursor.position();
            int end = cursor.indexOfChar(quote, start);
            int stop = end < 0 ? cursor.length() : end;
            String value = cursor.slice(start, stop);
            cursor.moveTo(end < 0 ? cursor.length() : end + 1);
            return value;
        }
        StringBuilder sb = new StringBuilder();
        while (cursor.hasNext()) {
            char c = cursor.peek();
            if (isWhitespace(c) || c == '>') {
                break;
            }
            sb.append(c);
            cursor.advance(1);
        }
        return sb.toString();
    }

    /**
     * RAWTEXT 模式：只找匹配的结束标签，中间内容整段当文本。
     *
     * <p>消费到结束标签的 {@code >} 之后；找不到结束标签时吃掉剩余全部输入（畸形 HTML 容错）。
     */
    private static String readRawText(Cursor cursor, String tag) {
        int start = cursor.position();
        int close = cursor.indexOfIgnoreCase("</" + tag, start);
        if (close < 0) {
            String raw = cursor.slice(start, cursor.length());
            cursor.moveTo(cursor.length());
            return raw;
        }
        String raw = cursor.slice(start, close);
        cursor.moveTo(close + 2 + tag.length());
        skipToTagEnd(cursor);
        return raw;
    }

    /** 跳过标签剩余部分直到 {@code >}（含）。 */
    private static void skipToTagEnd(Cursor cursor) {
        int end = cursor.indexOfChar('>', cursor.position());
        cursor.moveTo(end < 0 ? cursor.length() : end + 1);
    }

    private static void flushText(List<Token> tokens, StringBuilder text) {
        if (text.length() == 0) {
            return;
        }
        tokens.add(Token.text(text.toString()));
        text.setLength(0);
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':';
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    // ---------------------------------------------------------------- 嵌套类型

    /**
     * 扫描游标：把「当前位置」这个唯一的可变状态收在一个对象里。
     *
     * <p>{@link #tokenize} 每次调用都新建一个，因此扫描器本身是无状态、可并发复用的。
     */
    private static final class Cursor {

        private final char[] src;
        private int pos;

        /** 上一个被 {@link #readAttributes} 消费的标签是否以 {@code />} 结尾。 */
        private boolean lastTagSelfClosing;

        Cursor(char[] src) {
            this.src = src;
        }

        boolean hasNext() {
            return pos < src.length;
        }

        char peek() {
            return pos < src.length ? src[pos] : '\0';
        }

        char charAt(int index) {
            return (index >= 0 && index < src.length) ? src[index] : '\0';
        }

        int position() {
            return pos;
        }

        int length() {
            return src.length;
        }

        void advance(int step) {
            pos = Math.min(src.length, pos + step);
        }

        void moveTo(int target) {
            pos = Math.max(0, Math.min(src.length, target));
        }

        void skipWhitespace() {
            while (pos < src.length && isWhitespace(src[pos])) {
                pos++;
            }
        }

        String slice(int start, int end) {
            int from = Math.max(0, Math.min(start, src.length));
            int to = Math.max(from, Math.min(end, src.length));
            return new String(src, from, to - from);
        }

        boolean startsWith(String needle) {
            if (pos + needle.length() > src.length) {
                return false;
            }
            for (int i = 0; i < needle.length(); i++) {
                if (src[pos + i] != needle.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        int indexOfChar(char target, int from) {
            for (int i = Math.max(0, from); i < src.length; i++) {
                if (src[i] == target) {
                    return i;
                }
            }
            return -1;
        }

        int indexOf(String needle, int from) {
            int limit = src.length - needle.length();
            outer:
            for (int i = Math.max(0, from); i <= limit; i++) {
                for (int j = 0; j < needle.length(); j++) {
                    if (src[i + j] != needle.charAt(j)) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        int indexOfIgnoreCase(String needle, int from) {
            int limit = src.length - needle.length();
            outer:
            for (int i = Math.max(0, from); i <= limit; i++) {
                for (int j = 0; j < needle.length(); j++) {
                    if (Character.toLowerCase(src[i + j]) != Character.toLowerCase(needle.charAt(j))) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    /** token 类别。 */
    public enum Kind {
        /** 起始标签 {@code <p>}。 */
        START_TAG,
        /** 结束标签 {@code </p>}。 */
        END_TAG,
        /** 自闭合或 void 标签 {@code <br/>}。 */
        SELF_CLOSING,
        /** 文本内容（未做实体解码）。 */
        TEXT,
        /** 注释内容。 */
        COMMENT,
        /** DOCTYPE / 处理指令。 */
        DOCTYPE
    }

    /**
     * 一个 token。
     *
     * @param kind       类别
     * @param name       标签名（已小写）；非标签类为空串
     * @param attributes 属性表（键已小写）；非起始标签为空 Map
     * @param text       文本 / 注释原文；标签类为空串
     */
    public record Token(Kind kind, String name, Map<String, String> attributes, String text) {

        public Token {
            name = name == null ? "" : name;
            attributes = attributes == null ? Map.of() : attributes;
            text = text == null ? "" : text;
        }

        static Token start(String name, Map<String, String> attributes) {
            return new Token(Kind.START_TAG, name, attributes, "");
        }

        static Token end(String name) {
            return new Token(Kind.END_TAG, name, Map.of(), "");
        }

        static Token selfClosing(String name, Map<String, String> attributes) {
            return new Token(Kind.SELF_CLOSING, name, attributes, "");
        }

        static Token text(String text) {
            return new Token(Kind.TEXT, "", Map.of(), text);
        }

        static Token comment(String text) {
            return new Token(Kind.COMMENT, "", Map.of(), text);
        }

        static Token doctype(String text) {
            return new Token(Kind.DOCTYPE, "", Map.of(), text);
        }

        /** 是否是起始标签（含自闭合）。 */
        public boolean opens() {
            return kind == Kind.START_TAG || kind == Kind.SELF_CLOSING;
        }

        /** 取属性值，不存在时返回空串。 */
        public String attr(String key) {
            String value = attributes.get(key);
            return value == null ? "" : value;
        }
    }
}
