package com.agentcli.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码分块器：把源文件切成适合向量化的 {@link CodeChunk}。
 *
 * <p>采用<b>三级回退</b>策略（顺序不可颠倒）：
 * <ol>
 *     <li><b>类型级</b>——{@code .java} 文件按 class / interface / enum / record 切出「声明头 + 字段区」块；</li>
 *     <li><b>方法级</b>——每个类型体内的成员方法各切一块，块名为 {@code TypeName.methodName}；</li>
 *     <li><b>定长窗口</b>——非 Java 文件、无类型声明的 Java 文件、以及超过
 *         {@link #MAX_CHUNK_CHARS} 的超长方法，统一按整行边界切成带
 *         {@link #OVERLAP_CHARS} 字符重叠的定长窗口。</li>
 * </ol>
 *
 * <p>关键约束：
 * <ul>
 *     <li>任何块的 {@code content} 长度都 ≤ {@link #MAX_CHUNK_CHARS}（与 embedding 输入上限对齐）；</li>
 *     <li>所有行号为 <b>1-based 闭区间</b>，由 {@link JavaSourceScanner#lineOf} 在原始串上换算；</li>
 *     <li>空文件 / 全空白文件返回空列表，避免产生检索噪声；</li>
 *     <li><b>解析失败绝不外抛</b>——退化为定长窗口或空列表，保证单个畸形文件不会中断整次索引。</li>
 * </ul>
 */
public class CodeChunker {

    private static final Logger log = LoggerFactory.getLogger(CodeChunker.class);

    /** 单块字符硬上限。 */
    public static final int MAX_CHUNK_CHARS = 2000;
    /** 定长窗口相邻段之间的重叠字符数（按整行回退，避免把一个语义单元劈成两半）。 */
    public static final int OVERLAP_CHARS = 200;

    private final int maxChunkChars;
    private final int overlapChars;

    /** 使用默认上限（2000 字符 / 200 字符重叠）。 */
    public CodeChunker() {
        this(MAX_CHUNK_CHARS, OVERLAP_CHARS);
    }

    /**
     * @param maxChunkChars 单块字符上限，必须为正
     * @param overlapChars  相邻窗口重叠字符数，取值范围 {@code [0, maxChunkChars)}
     */
    public CodeChunker(int maxChunkChars, int overlapChars) {
        if (maxChunkChars <= 0) {
            throw new IllegalArgumentException("maxChunkChars 必须为正数: " + maxChunkChars);
        }
        if (overlapChars < 0 || overlapChars >= maxChunkChars) {
            throw new IllegalArgumentException("overlapChars 必须位于 [0, maxChunkChars): " + overlapChars);
        }
        this.maxChunkChars = maxChunkChars;
        this.overlapChars = overlapChars;
    }

    /**
     * 读取文件并分块；读取失败（编码 / 权限 / 二进制）返回空列表并记 {@code log.warn}。
     *
     * @param file         绝对路径
     * @param relativePath 相对项目根的路径（{@code /} 分隔），会写进每个块
     */
    public List<CodeChunk> chunkFile(Path file, String relativePath) {
        if (file == null || relativePath == null) {
            return List.of();
        }
        try {
            return chunkText(relativePath, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("读取文件失败，跳过分块: {}", file, e);
            return List.of();
        }
    }

    /**
     * 对已读入内存的文本分块。
     *
     * @param relativePath 相对项目根的路径（{@code /} 分隔）
     * @param content      文件全文
     * @return 块列表；空文件或全空白返回空列表
     */
    public List<CodeChunk> chunkText(String relativePath, String content) {
        if (relativePath == null || content == null || content.isBlank()) {
            return List.of();
        }
        try {
            if (relativePath.endsWith(".java")) {
                List<CodeChunk> chunks = chunkJava(relativePath, content);
                if (!chunks.isEmpty()) {
                    return chunks;
                }
            }
            return chunkFixedWindow(relativePath, relativePath, content, 1);
        } catch (RuntimeException e) {
            log.warn("分块失败，退化为定长窗口: {}", relativePath, e);
            try {
                return chunkFixedWindow(relativePath, relativePath, content, 1);
            } catch (RuntimeException fallbackFailure) {
                log.warn("定长窗口分块同样失败，跳过该文件: {}", relativePath, fallbackFailure);
                return List.of();
            }
        }
    }

    /** 类型级 + 方法级分块；无类型声明时返回空列表，交由调用方回退定长窗口。 */
    private List<CodeChunk> chunkJava(String relativePath, String content) {
        String masked = JavaSourceScanner.mask(content);
        List<JavaSourceScanner.Declaration> types = JavaSourceScanner.findTypeDeclarations(masked);
        if (types.isEmpty()) {
            return List.of();
        }
        int[] lineStarts = JavaSourceScanner.lineStarts(content);
        List<CodeChunk> chunks = new ArrayList<>();
        for (JavaSourceScanner.Declaration type : types) {
            List<JavaSourceScanner.Declaration> methods =
                    JavaSourceScanner.findMethodDeclarations(masked, type.bodyStart() + 1, type.bodyEnd());
            appendTypeChunk(chunks, relativePath, content, lineStarts, type, methods);
            for (JavaSourceScanner.Declaration method : methods) {
                appendMethodChunks(chunks, relativePath, content, lineStarts, type, method);
            }
        }
        return chunks;
    }

    /** 类型级块：从声明头一直到第一个方法起点（没有方法时取整个类型体）。 */
    private void appendTypeChunk(List<CodeChunk> sink, String relativePath, String content,
                                 int[] lineStarts, JavaSourceScanner.Declaration type,
                                 List<JavaSourceScanner.Declaration> methods) {
        int headerEnd = methods.isEmpty()
                ? Math.min(content.length(), type.bodyEnd() + 1)
                : Math.min(content.length(), methods.get(0).declStart());
        if (headerEnd <= type.declStart()) {
            return;
        }
        String header = truncate(content.substring(type.declStart(), headerEnd)).stripTrailing();
        if (header.isBlank()) {
            return;
        }
        int startLine = JavaSourceScanner.lineOf(lineStarts, type.declStart());
        int endLine = JavaSourceScanner.lineOf(lineStarts, type.declStart() + header.length() - 1);
        sink.add(CodeChunk.typeChunk(relativePath, type.kind(), type.name(), header,
                startLine, Math.max(startLine, endLine)));
    }

    /** 方法级块；超过上限的超长方法二次切分，块名追加 {@code #n}。 */
    private void appendMethodChunks(List<CodeChunk> sink, String relativePath, String content,
                                    int[] lineStarts, JavaSourceScanner.Declaration type,
                                    JavaSourceScanner.Declaration method) {
        int end = Math.min(content.length(), method.bodyEnd() + 1);
        if (end <= method.declStart()) {
            return;
        }
        String body = content.substring(method.declStart(), end);
        if (body.isBlank()) {
            return;
        }
        String name = type.name() + "." + method.name();
        int startLine = JavaSourceScanner.lineOf(lineStarts, method.declStart());
        if (body.length() <= maxChunkChars) {
            int endLine = JavaSourceScanner.lineOf(lineStarts, end - 1);
            sink.add(CodeChunk.methodChunk(relativePath, name, body, startLine, Math.max(startLine, endLine)));
            return;
        }
        List<Window> windows = splitWindows(body, startLine);
        int index = 1;
        for (Window window : windows) {
            sink.add(CodeChunk.methodChunk(relativePath, name + "#" + index,
                    window.text(), window.startLine(), window.endLine()));
            index++;
        }
    }

    /**
     * 定长窗口分块：按整行边界累积，相邻段重叠 {@link #overlapChars} 字符。
     *
     * @param relativePath 相对路径，写入块的 {@code filePath}
     * @param name         块名前缀，最终块名为 {@code name#n}
     * @param content      待切分文本
     * @param baseLine     {@code content} 首行在原文件中的行号（1-based）
     */
    private List<CodeChunk> chunkFixedWindow(String relativePath, String name, String content, int baseLine) {
        List<Window> windows = splitWindows(content, baseLine);
        List<CodeChunk> chunks = new ArrayList<>(windows.size());
        int index = 1;
        for (Window window : windows) {
            chunks.add(CodeChunk.fileChunk(relativePath, name + "#" + index,
                    window.text(), window.startLine(), window.endLine()));
            index++;
        }
        return chunks;
    }

    /**
     * 把文本切成带重叠的窗口。
     *
     * <p>优先在换行处切分；单行本身就超过上限时（压缩产物 / 长字符串）做硬切，
     * 此时该窗口的起止行号相同。全空白窗口会被丢弃。
     */
    private List<Window> splitWindows(String text, int baseLine) {
        List<Window> windows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return windows;
        }
        int length = text.length();
        int pos = 0;
        int lineOffset = 0;
        while (pos < length) {
            int scanEnd = Math.min(length, pos + maxChunkChars);
            int lastBreak = text.lastIndexOf('\n', scanEnd - 1);
            int cut;
            if (scanEnd >= length) {
                cut = length;
            } else if (lastBreak > pos) {
                cut = lastBreak + 1;
            } else {
                cut = scanEnd;
            }
            String piece = text.substring(pos, cut);
            int startLine = baseLine + lineOffset;
            int newlines = countNewlines(piece);
            int endLine = piece.endsWith("\n") ? startLine + newlines - 1 : startLine + newlines;
            if (!piece.isBlank()) {
                windows.add(new Window(piece.stripTrailing(), startLine, Math.max(startLine, endLine)));
            }
            if (cut >= length) {
                break;
            }
            int next = nextWindowStart(text, pos, cut);
            lineOffset += countNewlines(text.substring(pos, next));
            pos = next;
        }
        return windows;
    }

    /** 计算下一个窗口的起点：回退 {@link #overlapChars} 字符并对齐到整行；无法回退时不重叠。 */
    private int nextWindowStart(String text, int pos, int cut) {
        int target = cut - overlapChars;
        if (overlapChars <= 0 || target <= pos) {
            return cut;
        }
        int lastBreak = text.lastIndexOf('\n', target);
        int candidate = lastBreak + 1;
        return candidate > pos && candidate < cut ? candidate : cut;
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** 截断到单块上限，优先在换行处截断以保持代码可读。 */
    private String truncate(String text) {
        if (text.length() <= maxChunkChars) {
            return text;
        }
        String head = text.substring(0, maxChunkChars);
        int lastBreak = head.lastIndexOf('\n');
        return lastBreak > maxChunkChars / 2 ? head.substring(0, lastBreak) : head;
    }

    /** 定长窗口的中间产物：文本 + 1-based 闭区间行号。 */
    private record Window(String text, int startLine, int endLine) {
    }
}
