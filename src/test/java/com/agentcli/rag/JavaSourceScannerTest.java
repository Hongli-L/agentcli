package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JavaSourceScanner} 单元测试。
 *
 * <p>掩码「等长且换行位置不变」是行号正确性的基石，本测试直接断言该不变量。
 */
class JavaSourceScannerTest {

    private static String loadSample() throws IOException {
        try (InputStream in = JavaSourceScannerTest.class.getResourceAsStream("/rag/SampleService.java.txt")) {
            assertNotNull(in, "测试资源 /rag/SampleService.java.txt 缺失");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("掩码后长度不变且换行位置完全一致")
    void maskKeepsLengthAndLineBreaks() throws IOException {
        String source = loadSample();
        String masked = JavaSourceScanner.mask(source);

        assertEquals(source.length(), masked.length(), "掩码串必须与原串等长");
        assertEquals(countChar(source, '\n'), countChar(masked, '\n'), "换行数量必须一致");
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                assertEquals('\n', masked.charAt(i), "换行位置必须保持不变，偏移=" + i);
            }
        }
    }

    @Test
    @DisplayName("注释、字符串与文本块中的内容被整体掩码")
    void maskWipesCommentsStringsAndTextBlocks() throws IOException {
        String masked = JavaSourceScanner.mask(loadSample());

        assertFalse(masked.contains("Ghost"), "Javadoc 内容应被掩码");
        assertFalse(masked.contains("Shadow"), "行注释内容应被掩码");
        assertFalse(masked.contains("Fake"), "文本块内容应被掩码");
        assertFalse(masked.contains("default}value"), "字符串字面量应被掩码");
        assertTrue(masked.contains("class SampleService"), "真实代码必须保留");
    }

    @Test
    @DisplayName("注释里的 class Foo 不会被识别为类型声明")
    void commentTypeDeclarationIsIgnored() throws IOException {
        String masked = JavaSourceScanner.mask(loadSample());
        List<JavaSourceScanner.Declaration> types = JavaSourceScanner.findTypeDeclarations(masked);

        List<String> names = types.stream().map(JavaSourceScanner.Declaration::name).toList();
        assertTrue(names.contains("SampleService"));
        assertTrue(names.contains("Callback"));
        assertTrue(names.contains("Status"));
        assertFalse(names.contains("Ghost"));
        assertFalse(names.contains("Shadow"));
        assertFalse(names.contains("Fake"));
    }

    @Test
    @DisplayName("字符串里的右花括号不影响花括号配对")
    void stringBraceDoesNotBreakMatching() {
        String source = """
                class A {
                    void m() {
                        String s = "}";
                    }
                }
                """;
        String masked = JavaSourceScanner.mask(source);
        int open = masked.indexOf('{');

        assertEquals(source.lastIndexOf('}'), JavaSourceScanner.matchingBrace(masked, open));
    }

    @Test
    @DisplayName("未配对的花括号返回 -1 而不是抛异常")
    void unmatchedBraceReturnsMinusOne() {
        String masked = JavaSourceScanner.mask("class A {\n    void m() {\n");

        assertEquals(-1, JavaSourceScanner.matchingBrace(masked, masked.indexOf('{')));
        assertEquals(-1, JavaSourceScanner.matchingBrace(masked, -1));
        assertEquals(-1, JavaSourceScanner.matchingBrace(masked, 0));
        assertEquals(-1, JavaSourceScanner.matchingBrace(null, 0));
    }

    @Test
    @DisplayName("lineOf 返回 1-based 行号")
    void lineOfReturnsOneBasedLine() {
        String source = "line1\nline2\nline3";
        int[] starts = JavaSourceScanner.lineStarts(source);

        assertEquals(3, starts.length);
        assertEquals(1, JavaSourceScanner.lineOf(starts, 0));
        assertEquals(1, JavaSourceScanner.lineOf(starts, 4));
        assertEquals(2, JavaSourceScanner.lineOf(starts, 6));
        assertEquals(3, JavaSourceScanner.lineOf(starts, source.length() - 1));
        assertEquals(1, JavaSourceScanner.lineOf(starts, -5));
    }

    @Test
    @DisplayName("只识别成员层方法，方法体内的调用语句不被误判")
    void findsOnlyMemberLevelMethods() throws IOException {
        String source = loadSample();
        String masked = JavaSourceScanner.mask(source);
        JavaSourceScanner.Declaration type = JavaSourceScanner.findTypeDeclarations(masked).stream()
                .filter(d -> "SampleService".equals(d.name()))
                .findFirst()
                .orElseThrow();

        List<String> methods = JavaSourceScanner
                .findMethodDeclarations(masked, type.bodyStart() + 1, type.bodyEnd())
                .stream()
                .map(JavaSourceScanner.Declaration::name)
                .toList();

        assertTrue(methods.contains("SampleService"), "构造方法应被识别");
        assertTrue(methods.contains("recall"));
        assertTrue(methods.contains("remember"));
        assertTrue(methods.contains("describe"));
        assertFalse(methods.contains("println"), "方法体内的调用不能被当成声明");
        assertFalse(methods.contains("addAll"), "方法体内的调用不能被当成声明");
        assertFalse(methods.contains("onDone"), "嵌套类型的方法不属于外层类型");
    }

    @Test
    @DisplayName("接口的抽象方法以分号收尾同样被识别")
    void findsAbstractMethodTerminatedBySemicolon() throws IOException {
        String masked = JavaSourceScanner.mask(loadSample());
        JavaSourceScanner.Declaration callback = JavaSourceScanner.findTypeDeclarations(masked).stream()
                .filter(d -> "Callback".equals(d.name()))
                .findFirst()
                .orElseThrow();

        List<JavaSourceScanner.Declaration> methods =
                JavaSourceScanner.findMethodDeclarations(masked, callback.bodyStart() + 1, callback.bodyEnd());

        assertEquals(1, methods.size());
        assertEquals("onDone", methods.get(0).name());
        assertEquals(methods.get(0).bodyStart(), methods.get(0).bodyEnd(), "抽象方法的 body 起止相同");
    }

    @Test
    @DisplayName("变量类型映射覆盖字段与形参")
    void findVariableTypesCoversFieldsAndParameters() throws IOException {
        String source = loadSample();
        String masked = JavaSourceScanner.mask(source);
        JavaSourceScanner.Declaration type = JavaSourceScanner.findTypeDeclarations(masked).get(0);

        Map<String, String> variables =
                JavaSourceScanner.findVariableTypes(masked, type.declStart(), type.bodyEnd());

        assertEquals("MemoryManager", variables.get("memoryManager"));
        assertEquals("String", variables.get("query"));
        assertEquals("List", variables.get("hits"));
    }

    @Test
    @DisplayName("空输入一律安全返回")
    void emptyInputIsSafe() {
        assertEquals("", JavaSourceScanner.mask(null));
        assertEquals("", JavaSourceScanner.mask(""));
        assertTrue(JavaSourceScanner.findTypeDeclarations("").isEmpty());
        assertTrue(JavaSourceScanner.findMethodDeclarations("", 0, 10).isEmpty());
        assertTrue(JavaSourceScanner.findVariableTypes(null, 0, 10).isEmpty());
        assertEquals(-1, JavaSourceScanner.nextBrace("abc", 0));
    }
}
