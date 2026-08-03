package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CodeChunker} 单元测试：覆盖「类型级 → 方法级 → 定长窗口」三级回退。
 */
class CodeChunkerTest {

    private final CodeChunker chunker = new CodeChunker();

    private static String loadSample() throws IOException {
        try (InputStream in = CodeChunkerTest.class.getResourceAsStream("/rag/SampleService.java.txt")) {
            assertNotNull(in, "测试资源 /rag/SampleService.java.txt 缺失");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CodeChunk findByName(List<CodeChunk> chunks, String name) {
        return chunks.stream().filter(c -> name.equals(c.name())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Java 文件同时产出类型块与方法块，chunkType 覆盖 class/interface/enum/method")
    void javaFileProducesTypeAndMethodChunks() throws IOException {
        List<CodeChunk> chunks = chunker.chunkText("src/main/java/SampleService.java", loadSample());

        Set<String> types = chunks.stream().map(CodeChunk::chunkType).collect(Collectors.toSet());
        assertTrue(types.contains("class"), "应有 class 类型块");
        assertTrue(types.contains("interface"), "应有 interface 类型块");
        assertTrue(types.contains("enum"), "应有 enum 类型块");
        assertTrue(types.contains(CodeChunk.TYPE_METHOD), "应有 method 块");

        CodeChunk typeChunk = findByName(chunks, "SampleService");
        assertNotNull(typeChunk, "类型块缺失");
        assertEquals("class", typeChunk.chunkType());
        assertTrue(typeChunk.content().contains("class SampleService"));
        assertTrue(typeChunk.content().contains("private final MemoryManager memoryManager;"),
                "类型块应包含字段区");

        assertNotNull(findByName(chunks, "SampleService.recall"), "方法块 recall 缺失");
        assertNotNull(findByName(chunks, "SampleService.remember"), "方法块 remember 缺失");
        assertNotNull(findByName(chunks, "Callback.onDone"), "嵌套接口的方法块缺失");
    }

    @Test
    @DisplayName("toEmbeddingText 带 [class:SampleService] 结构化前缀")
    void embeddingTextCarriesStructuredPrefix() throws IOException {
        List<CodeChunk> chunks = chunker.chunkText("src/main/java/SampleService.java", loadSample());
        CodeChunk typeChunk = findByName(chunks, "SampleService");

        assertNotNull(typeChunk);
        assertTrue(typeChunk.toEmbeddingText().startsWith("[class:SampleService] src/main/java/SampleService.java"),
                "实际前缀: " + typeChunk.toEmbeddingText().substring(0, 60));
        assertEquals("src/main/java/SampleService.java:" + typeChunk.startLine() + "-" + typeChunk.endLine(),
                typeChunk.location());
    }

    @Test
    @DisplayName("所有块的行号为 1-based 闭区间且不越界，内容不超过上限")
    void lineRangesAreValidAndContentBounded() throws IOException {
        String source = loadSample();
        int totalLines = source.split("\n", -1).length;

        List<CodeChunk> chunks = chunker.chunkText("src/main/java/SampleService.java", source);

        assertFalse(chunks.isEmpty());
        for (CodeChunk chunk : chunks) {
            assertTrue(chunk.startLine() >= 1, chunk.name() + " 起始行必须 >= 1");
            assertTrue(chunk.endLine() >= chunk.startLine(), chunk.name() + " 结束行不得小于起始行");
            assertTrue(chunk.endLine() <= totalLines, chunk.name() + " 结束行不得越过文件总行数");
            assertTrue(chunk.content().length() <= CodeChunker.MAX_CHUNK_CHARS,
                    chunk.name() + " 超过单块上限");
        }

        CodeChunk typeChunk = findByName(chunks, "SampleService");
        assertNotNull(typeChunk);
        assertTrue(typeChunk.startLine() < typeChunk.endLine(), "多行类型块的起止行应当不同");
    }

    @Test
    @DisplayName("超长方法二次切分，块名追加 #n")
    void oversizedMethodIsSplitWithIndexedNames() {
        StringBuilder sb = new StringBuilder("class Big {\n    void huge() {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("        int value").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n}\n");

        List<CodeChunk> chunks = chunker.chunkText("src/main/java/Big.java", sb.toString());

        List<CodeChunk> methodChunks = chunks.stream()
                .filter(c -> c.name().startsWith("Big.huge"))
                .toList();
        assertTrue(methodChunks.size() >= 2, "超长方法应被切成多段，实际 " + methodChunks.size());
        assertEquals("Big.huge#1", methodChunks.get(0).name());
        assertEquals("Big.huge#2", methodChunks.get(1).name());
        for (CodeChunk chunk : methodChunks) {
            assertEquals(CodeChunk.TYPE_METHOD, chunk.chunkType());
            assertTrue(chunk.content().length() <= CodeChunker.MAX_CHUNK_CHARS);
        }
    }

    @Test
    @DisplayName("非 Java 文件走定长窗口且相邻段有重叠")
    void nonJavaFileUsesOverlappingFixedWindows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("第 ").append(i).append(" 行说明文字，用于凑够多个窗口。\n");
        }

        List<CodeChunk> chunks = chunker.chunkText("docs/notes.md", sb.toString());

        assertTrue(chunks.size() >= 2, "应切成多个窗口，实际 " + chunks.size());
        assertEquals(CodeChunk.TYPE_FILE, chunks.get(0).chunkType());
        assertEquals("docs/notes.md#1", chunks.get(0).name());
        assertEquals("docs/notes.md#2", chunks.get(1).name());
        assertTrue(chunks.get(1).startLine() <= chunks.get(0).endLine(),
                "相邻窗口应有行级重叠: " + chunks.get(0).endLine() + " vs " + chunks.get(1).startLine());
    }

    @Test
    @DisplayName("无类型声明的 Java 文件回退定长窗口")
    void javaFileWithoutTypeDeclarationFallsBack() {
        List<CodeChunk> chunks = chunker.chunkText("src/main/java/package-info.java",
                "// 仅有注释与一行语句的文件\nint counter = 1;\n");

        assertEquals(1, chunks.size());
        assertEquals(CodeChunk.TYPE_FILE, chunks.get(0).chunkType());
        assertEquals("src/main/java/package-info.java#1", chunks.get(0).name());
        assertEquals(1, chunks.get(0).startLine());
    }

    @Test
    @DisplayName("空文件与全空白文件返回空列表")
    void blankContentProducesNoChunks() {
        assertTrue(chunker.chunkText("Empty.java", "").isEmpty());
        assertTrue(chunker.chunkText("Empty.java", "   \n\n\t  ").isEmpty());
        assertTrue(chunker.chunkText("Empty.java", null).isEmpty());
        assertTrue(chunker.chunkText(null, "class A {}").isEmpty());
    }

    @Test
    @DisplayName("非法构造参数被拒绝")
    void invalidConstructorArgumentsRejected() {
        try {
            new CodeChunker(0, 0);
            throw new AssertionError("maxChunkChars 为 0 应当抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxChunkChars"));
        }
        try {
            new CodeChunker(100, 100);
            throw new AssertionError("overlapChars >= maxChunkChars 应当抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("overlapChars"));
        }
    }
}
