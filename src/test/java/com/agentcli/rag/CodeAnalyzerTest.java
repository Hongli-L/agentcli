package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CodeAnalyzer} 单元测试：四类关系与调用过滤规则。
 */
class CodeAnalyzerTest {

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    private static String loadSample() throws IOException {
        try (InputStream in = CodeAnalyzerTest.class.getResourceAsStream("/rag/SampleService.java.txt")) {
            assertNotNull(in, "测试资源 /rag/SampleService.java.txt 缺失");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> knownTypes(String content) {
        Set<String> types = new HashSet<>(CodeAnalyzer.collectTypeNames(content));
        // 模拟第一遍扫描时从其它文件收集到的项目内类型
        types.add("BaseService");
        types.add("ServiceInterface");
        types.add("MemoryManager");
        return types;
    }

    private static boolean has(List<CodeRelation> relations, String from, String to, String type) {
        return relations.stream().anyMatch(r -> from.equals(r.fromName())
                && to.equals(r.toName())
                && type.equals(r.relationType()));
    }

    private static long count(List<CodeRelation> relations, String from, String to, String type) {
        return relations.stream().filter(r -> from.equals(r.fromName())
                && to.equals(r.toName())
                && type.equals(r.relationType())).count();
    }

    @Test
    @DisplayName("collectTypeNames 收集全部类型声明且不含注释噪声")
    void collectTypeNamesIgnoresComments() throws IOException {
        Set<String> names = CodeAnalyzer.collectTypeNames(loadSample());

        assertTrue(names.contains("SampleService"));
        assertTrue(names.contains("Callback"));
        assertTrue(names.contains("Status"));
        assertFalse(names.contains("Ghost"));
        assertFalse(names.contains("Fake"));
        assertTrue(CodeAnalyzer.collectTypeNames(null).isEmpty());
        assertTrue(CodeAnalyzer.collectTypeNames("   ").isEmpty());
    }

    @Test
    @DisplayName("四类关系各产出至少一条")
    void producesAllFourRelationTypes() throws IOException {
        String content = loadSample();
        String path = "src/main/java/SampleService.java";

        List<CodeRelation> relations = analyzer.analyze(path, content, knownTypes(content));

        assertTrue(has(relations, "SampleService", "BaseService", CodeRelation.EXTENDS), "缺少 extends 关系");
        assertTrue(has(relations, "SampleService", "ServiceInterface", CodeRelation.IMPLEMENTS),
                "缺少 implements 关系");
        assertTrue(has(relations, "SampleService", "SampleService.recall", CodeRelation.CONTAINS),
                "缺少 contains 关系");
        assertTrue(has(relations, "SampleService.recall", "MemoryManager.recall", CodeRelation.CALLS),
                "缺少 calls 关系");

        CodeRelation contains = relations.stream()
                .filter(r -> CodeRelation.CONTAINS.equals(r.relationType()))
                .findFirst()
                .orElseThrow();
        assertEquals(path, contains.fromFile());
        assertEquals(path, contains.toFile(), "contains 的 toFile 与 fromFile 相同");

        CodeRelation extendsRelation = relations.stream()
                .filter(r -> CodeRelation.EXTENDS.equals(r.relationType()))
                .findFirst()
                .orElseThrow();
        assertEquals(null, extendsRelation.toFile(), "extends 的 toFile 为 null");
    }

    @Test
    @DisplayName("字段类型映射生效：memoryManager.recall() 解析为 MemoryManager.recall")
    void fieldTypeMappingResolvesCallReceiver() throws IOException {
        String content = loadSample();

        List<CodeRelation> relations = analyzer.analyze("SampleService.java", content, knownTypes(content));

        assertTrue(has(relations, "SampleService.recall", "MemoryManager.recall", CodeRelation.CALLS));
        assertTrue(has(relations, "SampleService.remember", "MemoryManager.remember", CodeRelation.CALLS));
    }

    @Test
    @DisplayName("项目外调用被过滤：System.out.println 与 hits.addAll 不入图谱")
    void outOfProjectCallsAreFiltered() throws IOException {
        String content = loadSample();

        List<CodeRelation> relations = analyzer.analyze("SampleService.java", content, knownTypes(content));

        assertTrue(relations.stream().noneMatch(r -> r.toName().contains("println")),
                "System.out.println 不应入图谱");
        assertTrue(relations.stream().noneMatch(r -> r.toName().contains("addAll")),
                "List.addAll 不应入图谱");
    }

    @Test
    @DisplayName("注释与文本块中的 extends 不产生关系")
    void commentInheritanceIsIgnored() throws IOException {
        String content = loadSample();

        List<CodeRelation> relations = analyzer.analyze("SampleService.java", content, knownTypes(content));

        assertTrue(relations.stream().noneMatch(r -> "Phantom".equals(r.toName())));
        assertTrue(relations.stream().noneMatch(r -> "Darkness".equals(r.toName())));
        assertTrue(relations.stream().noneMatch(r -> "Nothing".equals(r.toName())));
        assertTrue(relations.stream().noneMatch(r -> "Invisible".equals(r.toName())));
    }

    @Test
    @DisplayName("同一调用重复出现只落一条关系")
    void duplicateCallsAreDeduplicated() throws IOException {
        String content = loadSample();

        List<CodeRelation> relations = analyzer.analyze("SampleService.java", content, knownTypes(content));

        assertEquals(1, count(relations, "SampleService.recall", "MemoryManager.recall", CodeRelation.CALLS),
                "recall() 在方法体内被调用两次，只应落一条关系");
    }

    @Test
    @DisplayName("knownTypes 为空时不产生任何 calls 关系")
    void emptyKnownTypesProducesNoCalls() throws IOException {
        String content = loadSample();

        List<CodeRelation> relations = analyzer.analyze("SampleService.java", content, Set.of());

        assertTrue(relations.stream().noneMatch(r -> CodeRelation.CALLS.equals(r.relationType())));
        assertTrue(has(relations, "SampleService", "SampleService.recall", CodeRelation.CONTAINS),
                "contains 关系不依赖 knownTypes");
    }

    @Test
    @DisplayName("空输入与畸形输入安全返回空列表")
    void malformedInputIsSafe() {
        assertTrue(analyzer.analyze(null, "class A {}", Set.of()).isEmpty());
        assertTrue(analyzer.analyze("A.java", null, Set.of()).isEmpty());
        assertTrue(analyzer.analyze("A.java", "   ", Set.of()).isEmpty());
        assertTrue(analyzer.analyze("A.java", "class Broken {  void m() { ", Set.of()).isEmpty(),
                "花括号未配对的文件应被跳过而不是抛异常");
        assertTrue(analyzer.analyze("A.java", "class A {}", null).isEmpty(),
                "空类没有成员，关系列表为空");
    }
}
