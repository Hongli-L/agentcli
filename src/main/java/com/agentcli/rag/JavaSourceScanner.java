package com.agentcli.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于「掩码 + 括号配对」的轻量 Java 源码扫描器。
 *
 * <p>本项目坚持零新增依赖，因此不引入 JavaParser 之类的完整语法分析器。折中方案是：
 * 先把注释、字符串、字符字面量、文本块整体替换为<b>等长空格</b>（换行位置原样保留），
 * 得到一份「掩码串」；后续所有正则匹配与花括号配对都在掩码串上进行，而截取代码文本时
 * 用同一偏移量回到原始串。这样既避免了注释里的 {@code class Foo}、字符串里的 <code>"}"</code>
 * 干扰解析，又能保证行号完全准确。
 *
 * <p>设计取舍：
 * <ul>
 *     <li>不做完整语法树，只识别「类型声明」「方法声明」「变量声明」三类结构——
 *         这三类已足够支撑分块与四类关系抽取，复杂度换取的收益不成正比；</li>
 *     <li>所有方法都是<b>静态、无状态、不抛异常</b>的纯函数，解析不了就返回空集合 / {@code -1}，
 *         由调用方决定回退策略（分块回退定长窗口、关系抽取跳过该文件）；</li>
 *     <li>方法声明只在「成员层」（相对类型体花括号深度为 0）识别，天然排除方法体内的调用语句，
 *         同时天然把嵌套类型的成员留给嵌套类型自己处理。</li>
 * </ul>
 *
 * <p>包级可见的 final 工具类，仅供 {@link CodeChunker} 与 {@link CodeAnalyzer} 使用。
 */
final class JavaSourceScanner {

    /** 类型声明：class / interface / enum / record，排除 {@code @interface} 与 {@code Foo.class}。 */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?<![\\w.$@])(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    /** 变量（字段 / 形参 / 局部变量）声明，作用域简化为类级合并。 */
    private static final Pattern VARIABLE_DECL = Pattern.compile(
            "\\b([A-Z]\\w*)(?:<[^;={}()]*>)?(?:\\[\\])?\\s+(\\w+)\\s*[;=,)]");

    /** 带参数的注解，做前缀合法性校验前先擦掉（否则括号会让前缀判定失败）。 */
    private static final Pattern ANNOTATION_WITH_ARGS = Pattern.compile("@[\\w.]+\\s*\\([^()]*\\)");

    /** 合法的方法声明前缀字符集（修饰符 + 泛型 + 类型名 + 注解）。 */
    private static final Pattern DECL_PREFIX_CHARSET = Pattern.compile("[\\w$@.<>\\[\\],?&\\s]*");

    /** 前缀中最后一个单词若命中这些关键字，说明不是方法声明（而是语句 / 类型声明）。 */
    private static final Set<String> REJECTED_PREFIX_TOKENS = Set.of(
            "new", "return", "throw", "class", "interface", "enum", "record",
            "else", "do", "try", "case", "yield", "assert", "instanceof",
            "extends", "implements", "package", "import");

    /** 这些关键字后面跟括号是控制结构，不是方法声明 / 方法调用。 */
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "return",
            "new", "this", "super", "do", "try", "else", "assert", "throw",
            "instanceof", "yield", "case");

    private JavaSourceScanner() {
    }

    /**
     * 一个声明（类型或方法）的位置信息。
     *
     * @param kind      {@code class}/{@code interface}/{@code enum}/{@code record}/{@code method}
     * @param name      简单名（类型名或方法名，不含所属类型前缀）
     * @param declStart 声明起点（含修饰符与注解，不含 Javadoc），原始串偏移
     * @param bodyStart 方法体 / 类型体左花括号偏移；抽象方法为其结尾分号偏移
     * @param bodyEnd   与 {@code bodyStart} 配对的右花括号偏移；抽象方法与 {@code bodyStart} 相同
     */
    record Declaration(String kind, String name, int declStart, int bodyStart, int bodyEnd) {

        /** 声明整体的结束偏移（闭区间）。 */
        int declEnd() {
            return bodyEnd;
        }
    }

    // ------------------------------------------------------------------ 掩码

    /**
     * 把注释、字符串、字符字面量、文本块替换为等长空格。
     *
     * <p><b>硬约束</b>：返回串与入参<b>等长</b>，且所有 {@code '\n'} 位置不变——
     * 这是后续一切行号计算正确的前提，单测会直接断言。
     *
     * @param source 原始源码；null 视为空串
     * @return 掩码串
     */
    static String mask(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        char[] out = source.toCharArray();
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i = maskLineComment(source, out, i, n);
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i = maskBlockComment(source, out, i, n);
            } else if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                i = maskTextBlock(source, out, i, n);
            } else if (c == '"') {
                i = maskQuoted(source, out, i, n, '"');
            } else if (c == '\'') {
                i = maskQuoted(source, out, i, n, '\'');
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int maskLineComment(String source, char[] out, int start, int n) {
        int i = start;
        while (i < n && source.charAt(i) != '\n') {
            out[i] = ' ';
            i++;
        }
        return i;
    }

    private static int maskBlockComment(String source, char[] out, int start, int n) {
        out[start] = ' ';
        out[start + 1] = ' ';
        int i = start + 2;
        while (i < n) {
            if (source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                out[i] = ' ';
                out[i + 1] = ' ';
                return i + 2;
            }
            if (source.charAt(i) != '\n') {
                out[i] = ' ';
            }
            i++;
        }
        return i;
    }

    private static int maskTextBlock(String source, char[] out, int start, int n) {
        out[start] = ' ';
        out[start + 1] = ' ';
        out[start + 2] = ' ';
        int i = start + 3;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < n) {
                out[i] = ' ';
                if (source.charAt(i + 1) != '\n') {
                    out[i + 1] = ' ';
                }
                i += 2;
                continue;
            }
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                out[i] = ' ';
                out[i + 1] = ' ';
                out[i + 2] = ' ';
                return i + 3;
            }
            if (c != '\n') {
                out[i] = ' ';
            }
            i++;
        }
        return i;
    }

    /** 掩码单引号 / 双引号字面量；遇到未闭合的行尾即停止（容错，不吞掉后续代码）。 */
    private static int maskQuoted(String source, char[] out, int start, int n, char quote) {
        out[start] = ' ';
        int i = start + 1;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (c == '\\' && i + 1 < n) {
                out[i] = ' ';
                if (source.charAt(i + 1) != '\n') {
                    out[i + 1] = ' ';
                }
                i += 2;
                continue;
            }
            out[i] = ' ';
            i++;
            if (c == quote) {
                return i;
            }
        }
        return i;
    }

    // ------------------------------------------------------------- 括号与行号

    /** 从 {@code fromIndex} 起第一个左花括号的偏移；没有返回 {@code -1}。 */
    static int nextBrace(String masked, int fromIndex) {
        if (masked == null || fromIndex < 0) {
            return -1;
        }
        return masked.indexOf('{', fromIndex);
    }

    /**
     * 在掩码串上做深度计数，找到与 {@code openBraceIndex} 配对的右花括号。
     *
     * @return 右花括号偏移；入参非法或未配对返回 {@code -1}（调用方应跳过该声明而非中断整个文件）
     */
    static int matchingBrace(String masked, int openBraceIndex) {
        if (masked == null || openBraceIndex < 0 || openBraceIndex >= masked.length()) {
            return -1;
        }
        if (masked.charAt(openBraceIndex) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = openBraceIndex; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 与 {@link #matchingBrace} 同理的圆括号配对。 */
    static int matchingParen(String masked, int openParenIndex) {
        if (masked == null || openParenIndex < 0 || openParenIndex >= masked.length()) {
            return -1;
        }
        if (masked.charAt(openParenIndex) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openParenIndex; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 每一行起始偏移的数组，{@code [0] == 0}；供 {@link #lineOf} 二分查找。 */
    static int[] lineStarts(String source) {
        if (source == null || source.isEmpty()) {
            return new int[]{0};
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    /** 偏移量所在行号（<b>1-based</b>）；越界一律夹到合法范围。 */
    static int lineOf(int[] lineStarts, int offset) {
        if (lineStarts == null || lineStarts.length == 0) {
            return 1;
        }
        if (offset <= 0) {
            return 1;
        }
        int lo = 0;
        int hi = lineStarts.length - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= offset) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans + 1;
    }

    // --------------------------------------------------------------- 声明识别

    /**
     * 找出掩码串中<b>全部</b>类型声明（含嵌套类型），按出现顺序返回。
     *
     * <p>未配对花括号的声明会被跳过；声明头与左花括号之间出现 {@code ;} 或 {@code =}
     * 的也会被跳过（防止把 {@code Foo.class} 之类的表达式误判为声明）。
     */
    static List<Declaration> findTypeDeclarations(String masked) {
        List<Declaration> declarations = new ArrayList<>();
        if (masked == null || masked.isEmpty()) {
            return declarations;
        }
        Matcher matcher = TYPE_DECL.matcher(masked);
        while (matcher.find()) {
            int brace = nextBrace(masked, matcher.end());
            if (brace < 0) {
                continue;
            }
            String between = masked.substring(matcher.end(), brace);
            if (between.indexOf(';') >= 0 || between.indexOf('=') >= 0) {
                continue;
            }
            int bodyEnd = matchingBrace(masked, brace);
            if (bodyEnd < 0) {
                continue;
            }
            int declStart = declarationStart(masked, matcher.start());
            declarations.add(new Declaration(matcher.group(1), matcher.group(2), declStart, brace, bodyEnd));
        }
        return declarations;
    }

    /**
     * 找出 {@code [fromIndex, toIndex)} 区间内<b>成员层</b>的方法声明。
     *
     * <p>典型调用方式是 {@code findMethodDeclarations(masked, type.bodyStart() + 1, type.bodyEnd())}。
     * 扫描过程维护花括号深度，只在深度 0 处识别方法——因此方法体内部的调用语句、
     * 以及嵌套类型内部的方法都不会被误收（嵌套类型的方法由嵌套类型自己那一轮负责）。
     *
     * <p>同时支持带方法体的方法（{@code bodyStart} 为 <code>{</code>）与接口 / 抽象方法
     * （{@code bodyStart == bodyEnd == } 结尾分号偏移）。
     */
    static List<Declaration> findMethodDeclarations(String masked, int fromIndex, int toIndex) {
        List<Declaration> methods = new ArrayList<>();
        if (masked == null || masked.isEmpty()) {
            return methods;
        }
        int from = Math.max(0, fromIndex);
        int to = Math.min(masked.length(), toIndex);
        if (from >= to) {
            return methods;
        }

        int depth = 0;
        int memberStart = from;
        int i = from;
        while (i < to) {
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 1) {
                    memberStart = i + 1;
                }
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth <= 0) {
                    depth = 0;
                    memberStart = i + 1;
                }
                i++;
                continue;
            }
            if (depth > 0) {
                i++;
                continue;
            }
            if (c == ';') {
                memberStart = i + 1;
                i++;
                continue;
            }
            if (isIdentifierStart(c)) {
                int nameEnd = i;
                while (nameEnd < to && isIdentifierPart(masked.charAt(nameEnd))) {
                    nameEnd++;
                }
                int paren = skipWhitespace(masked, nameEnd, to);
                if (paren < to && masked.charAt(paren) == '(') {
                    Declaration method = tryMethodDeclaration(masked, memberStart, i, nameEnd, paren, to);
                    if (method != null) {
                        methods.add(method);
                        i = method.bodyEnd() + 1;
                        memberStart = i;
                        continue;
                    }
                }
                i = nameEnd;
                continue;
            }
            i++;
        }
        return methods;
    }

    private static Declaration tryMethodDeclaration(String masked, int memberStart, int nameStart,
                                                    int nameEnd, int parenIndex, int to) {
        String name = masked.substring(nameStart, nameEnd);
        if (CONTROL_KEYWORDS.contains(name)) {
            return null;
        }
        int declStart = skipWhitespace(masked, Math.min(memberStart, nameStart), nameStart);
        int prev = prevNonWhitespace(masked, nameStart - 1, declStart);
        if (prev >= 0 && masked.charAt(prev) == '.') {
            return null;
        }
        if (!isDeclarationPrefix(masked.substring(declStart, nameStart))) {
            return null;
        }
        int closeParen = matchingParen(masked, parenIndex);
        if (closeParen < 0 || closeParen >= to) {
            return null;
        }
        int p = closeParen + 1;
        // 跳过 throws 子句（标识符 / 逗号 / 点号 / 泛型符号 / 空白）
        while (p < to) {
            char c = masked.charAt(p);
            if (Character.isWhitespace(c) || isIdentifierPart(c) || c == ',' || c == '.' || c == '<' || c == '>') {
                p++;
                continue;
            }
            break;
        }
        if (p >= to) {
            return null;
        }
        char terminator = masked.charAt(p);
        if (terminator == '{') {
            int bodyEnd = matchingBrace(masked, p);
            if (bodyEnd < 0 || bodyEnd >= to) {
                return null;
            }
            return new Declaration("method", name, declStart, p, bodyEnd);
        }
        if (terminator == ';') {
            return new Declaration("method", name, declStart, p, p);
        }
        return null;
    }

    /** 前缀必须是「注解 + 修饰符 + 返回类型」的组合，借此排除 {@code x = foo()} / {@code return foo()} 之类的语句。 */
    private static boolean isDeclarationPrefix(String prefix) {
        String cleaned = ANNOTATION_WITH_ARGS.matcher(prefix).replaceAll(" ");
        if (cleaned.isBlank()) {
            return false;
        }
        if (!DECL_PREFIX_CHARSET.matcher(cleaned).matches()) {
            return false;
        }
        String lastToken = lastWord(cleaned);
        return lastToken != null && !REJECTED_PREFIX_TOKENS.contains(lastToken);
    }

    private static String lastWord(String text) {
        int end = text.length();
        while (end > 0 && !isIdentifierPart(text.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        int start = end;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    /**
     * 抽取 {@code [fromIndex, toIndex)} 内的「变量名 → 类型简单名」映射。
     *
     * <p>覆盖字段声明、方法形参与局部变量声明；作用域简化为<b>类级合并</b>——
     * 不做块级作用域分析（同名变量在不同方法里类型不同时以最后一次声明为准）。
     * 这是零依赖方案下的已知精度损失，对调用关系抽取的影响可以接受。
     */
    static Map<String, String> findVariableTypes(String masked, int fromIndex, int toIndex) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (masked == null || masked.isEmpty()) {
            return variables;
        }
        int from = Math.max(0, fromIndex);
        int to = Math.min(masked.length(), toIndex);
        if (from >= to) {
            return variables;
        }
        Matcher matcher = VARIABLE_DECL.matcher(masked.substring(from, to));
        while (matcher.find()) {
            String type = matcher.group(1);
            String variable = matcher.group(2);
            if (type.equals(variable)) {
                continue;
            }
            variables.put(variable, type);
        }
        return variables;
    }

    // ------------------------------------------------------------------ 杂项

    /** 声明起点：向前回溯到上一个 {@code ;}/<code>{</code>/<code>}</code>，再跳过空白（Javadoc 已被掩码成空白）。 */
    private static int declarationStart(String masked, int matchStart) {
        int i = matchStart - 1;
        while (i >= 0) {
            char c = masked.charAt(i);
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            i--;
        }
        int start = skipWhitespace(masked, i + 1, matchStart);
        return Math.min(start, matchStart);
    }

    private static int skipWhitespace(String masked, int fromIndex, int limit) {
        int i = Math.max(0, fromIndex);
        while (i < limit && Character.isWhitespace(masked.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int prevNonWhitespace(String masked, int fromIndex, int lowerBound) {
        int i = fromIndex;
        while (i >= lowerBound && i >= 0 && Character.isWhitespace(masked.charAt(i))) {
            i--;
        }
        return i >= lowerBound ? i : -1;
    }

    static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
