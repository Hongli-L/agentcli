package com.agentcli.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 类关系抽取器：从 Java 源码中提取 {@code contains} / {@code extends} / {@code implements} / {@code calls} 四类关系。
 *
 * <p>同样建立在 {@link JavaSourceScanner} 的掩码串之上，因此注释里的
 * {@code extends Foo}、字符串里的 {@code obj.call()} 都不会产生噪声关系。
 *
 * <p><b>两遍扫描</b>是本类的核心设计：
 * <ol>
 *     <li>第一遍 {@link #collectTypeNames(String)} 遍历全项目，收集「项目内类型简单名」集合；</li>
 *     <li>第二遍 {@link #analyze(String, String, Set)} 携带该集合逐文件抽取，
 *         只保留<b>接收者类型属于项目内</b>的调用。</li>
 * </ol>
 * 没有第一遍，{@code System.out.println()}、{@code list.add()} 这类 JDK / 三方调用会把图谱淹没，
 * 图谱也就失去了「看清本项目结构」的价值。
 *
 * <p>纪律：任何解析异常都被捕获并返回空集合，绝不外抛——抽取失败的文件跳过，不中断整次索引。
 */
public class CodeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzer.class);

    /** 方法调用：{@code receiver.method(}。 */
    private static final Pattern CALL = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");

    /** 继承 / 实现列表中的简单名（已剥离泛型与包名前缀）。 */
    private static final Pattern SIMPLE_NAME = Pattern.compile("([A-Za-z_$][\\w$]*)");

    /** 不能作为调用接收者的关键字。 */
    private static final Set<String> NON_RECEIVER_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "return",
            "new", "this", "super", "do", "try", "else", "assert", "throw",
            "instanceof", "yield", "case");

    /**
     * 第一遍扫描：收集单个文件中声明的全部类型简单名（含嵌套类型）。
     *
     * @param content 源码全文
     * @return 类型简单名集合；解析失败返回空集合
     */
    public static Set<String> collectTypeNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return names;
        }
        try {
            String masked = JavaSourceScanner.mask(content);
            for (JavaSourceScanner.Declaration type : JavaSourceScanner.findTypeDeclarations(masked)) {
                names.add(type.name());
            }
        } catch (RuntimeException e) {
            log.warn("收集类型名失败，按无类型处理", e);
        }
        return names;
    }

    /**
     * 第二遍扫描：抽取单个文件的四类关系。
     *
     * @param relativePath 相对项目根的路径（{@code /} 分隔），写入 {@code fromFile}
     * @param content      源码全文
     * @param knownTypes   全项目类型简单名集合，用于过滤 JDK / 三方调用；null 视为空集
     * @return 去重后的关系列表；解析失败返回空列表（绝不外抛）
     */
    public List<CodeRelation> analyze(String relativePath, String content, Set<String> knownTypes) {
        if (relativePath == null || content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> types = knownTypes == null ? Set.of() : knownTypes;
        Set<CodeRelation> relations = new LinkedHashSet<>();
        try {
            String masked = JavaSourceScanner.mask(content);
            for (JavaSourceScanner.Declaration type : JavaSourceScanner.findTypeDeclarations(masked)) {
                List<JavaSourceScanner.Declaration> methods =
                        JavaSourceScanner.findMethodDeclarations(masked, type.bodyStart() + 1, type.bodyEnd());
                extractInheritance(relativePath, masked, type, relations);
                extractContains(relativePath, type, methods, relations);
                extractCalls(relativePath, masked, type, methods, types, relations);
            }
        } catch (RuntimeException e) {
            log.warn("关系抽取失败，跳过该文件: {}", relativePath, e);
            return List.of();
        }
        return new ArrayList<>(relations);
    }

    /** {@code extends} / {@code implements}：只记录简单名，跨文件解析交给图谱查询时按名字对齐。 */
    private void extractInheritance(String relativePath, String masked,
                                    JavaSourceScanner.Declaration type, Set<CodeRelation> sink) {
        String header = masked.substring(type.declStart(), type.bodyStart());
        int extendsAt = indexOfKeyword(header, "extends");
        int implementsAt = indexOfKeyword(header, "implements");

        if (extendsAt >= 0) {
            int end = implementsAt > extendsAt ? implementsAt : header.length();
            for (String parent : simpleNames(header.substring(extendsAt + "extends".length(), end))) {
                sink.add(new CodeRelation(relativePath, type.name(), null, parent, CodeRelation.EXTENDS));
            }
        }
        if (implementsAt >= 0) {
            int end = extendsAt > implementsAt ? extendsAt : header.length();
            for (String api : simpleNames(header.substring(implementsAt + "implements".length(), end))) {
                sink.add(new CodeRelation(relativePath, type.name(), null, api, CodeRelation.IMPLEMENTS));
            }
        }
    }

    /** {@code contains}：类型 → 其直接成员方法。 */
    private void extractContains(String relativePath, JavaSourceScanner.Declaration type,
                                 List<JavaSourceScanner.Declaration> methods, Set<CodeRelation> sink) {
        for (JavaSourceScanner.Declaration method : methods) {
            sink.add(new CodeRelation(relativePath, type.name(), relativePath,
                    type.name() + "." + method.name(), CodeRelation.CONTAINS));
        }
    }

    /**
     * {@code calls}：方法体内对<b>项目内类型</b>的调用。
     *
     * <p>三个保留条件缺一不可：形如 {@code receiver.method(}；接收者是项目内类型的静态调用，
     * 或接收者变量的类型属于项目内类型；同一 {@code (from, to)} 只落一条。
     */
    private void extractCalls(String relativePath, String masked, JavaSourceScanner.Declaration type,
                              List<JavaSourceScanner.Declaration> methods, Set<String> knownTypes,
                              Set<CodeRelation> sink) {
        Map<String, String> variableTypes =
                JavaSourceScanner.findVariableTypes(masked, type.declStart(), type.bodyEnd());
        for (JavaSourceScanner.Declaration method : methods) {
            if (method.bodyEnd() <= method.bodyStart()) {
                continue;
            }
            String body = masked.substring(method.bodyStart(), method.bodyEnd() + 1);
            String from = type.name() + "." + method.name();
            Matcher matcher = CALL.matcher(body);
            while (matcher.find()) {
                if (matcher.start() > 0 && body.charAt(matcher.start() - 1) == '.') {
                    continue;
                }
                String receiver = matcher.group(1);
                String callee = matcher.group(2);
                if (NON_RECEIVER_KEYWORDS.contains(receiver) || NON_RECEIVER_KEYWORDS.contains(callee)) {
                    continue;
                }
                String receiverType = resolveReceiverType(receiver, variableTypes, knownTypes);
                if (receiverType == null) {
                    continue;
                }
                sink.add(new CodeRelation(relativePath, from, null,
                        receiverType + "." + callee, CodeRelation.CALLS));
            }
        }
    }

    /** 静态调用（接收者本身就是项目内类型）优先，其次查「变量 → 类型」映射。 */
    private String resolveReceiverType(String receiver, Map<String, String> variableTypes, Set<String> knownTypes) {
        if (!receiver.isEmpty() && Character.isUpperCase(receiver.charAt(0)) && knownTypes.contains(receiver)) {
            return receiver;
        }
        String mapped = variableTypes.get(receiver);
        if (mapped != null && knownTypes.contains(mapped)) {
            return mapped;
        }
        return null;
    }

    /** 在声明头里定位关键字（要求前后为非标识符字符，避免命中 {@code ServiceInterface} 之类）。 */
    private static int indexOfKeyword(String header, String keyword) {
        int from = 0;
        while (from <= header.length() - keyword.length()) {
            int at = header.indexOf(keyword, from);
            if (at < 0) {
                return -1;
            }
            boolean leftOk = at == 0 || !JavaSourceScanner.isIdentifierPart(header.charAt(at - 1));
            int after = at + keyword.length();
            boolean rightOk = after >= header.length() || !JavaSourceScanner.isIdentifierPart(header.charAt(after));
            if (leftOk && rightOk) {
                return at;
            }
            from = at + 1;
        }
        return -1;
    }

    /** 从 {@code A<T>, b.c.D} 这样的片段里取出简单名列表（泛型参数与包名被丢弃）。 */
    private static List<String> simpleNames(String fragment) {
        List<String> names = new ArrayList<>();
        for (String part : stripGenerics(fragment).split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int dot = trimmed.lastIndexOf('.');
            String simple = dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
            Matcher matcher = SIMPLE_NAME.matcher(simple);
            if (matcher.find() && matcher.start() == 0) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** 去掉尖括号内的泛型参数，保留逗号分隔的顶层结构。 */
    private static String stripGenerics(String fragment) {
        StringBuilder sb = new StringBuilder(fragment.length());
        int depth = 0;
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (c == '<') {
                depth++;
                continue;
            }
            if (c == '>') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
