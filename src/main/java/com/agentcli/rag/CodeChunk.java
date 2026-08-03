package com.agentcli.rag;

/**
 * 代码块：语义索引的最小单元。
 *
 * <p>由 {@link CodeChunker} 按「类型级 → 方法级 → 定长窗口」三级策略切分产出，
 * 携带 {@code filePath}/{@code chunkType}/{@code name}/起止行号，使检索结果可以直接定位到
 * {@code src/main/java/.../AuthService.java:42-78} 这样的精确区间，而不是只给出文件名。
 *
 * <p>约定：
 * <ul>
 *     <li>{@code filePath} 为相对项目根的路径，分隔符统一为 {@code /}（跨机器可移植）；</li>
 *     <li>{@code startLine}/{@code endLine} 为 <b>1-based 闭区间</b>；</li>
 *     <li>{@code content} 长度不超过 {@link CodeChunker#MAX_CHUNK_CHARS}。</li>
 * </ul>
 */
public record CodeChunk(
        String filePath,
        String chunkType,
        String name,
        String content,
        int startLine,
        int endLine) {

    /** 定长窗口块：整文件或超长方法二次切分的产物。 */
    public static final String TYPE_FILE = "file";
    /** 方法级块。 */
    public static final String TYPE_METHOD = "method";

    /**
     * 构造定长窗口块。
     *
     * @param filePath  相对项目根的文件路径
     * @param name      块名称，形如 {@code src/main/java/Foo.java#1}
     * @param content   代码文本
     * @param startLine 起始行（1-based，闭区间）
     * @param endLine   结束行（1-based，闭区间）
     */
    public static CodeChunk fileChunk(String filePath, String name, String content,
                                      int startLine, int endLine) {
        return new CodeChunk(filePath, TYPE_FILE, name, content, startLine, endLine);
    }

    /**
     * 构造类型级块（类 / 接口 / 枚举 / 记录的声明头与字段区）。
     *
     * @param kind {@code class} / {@code interface} / {@code enum} / {@code record}
     * @param name 类型简单名
     */
    public static CodeChunk typeChunk(String filePath, String kind, String name, String content,
                                      int startLine, int endLine) {
        return new CodeChunk(filePath, kind, name, content, startLine, endLine);
    }

    /**
     * 构造方法级块。
     *
     * @param name 形如 {@code TypeName.methodName}
     */
    public static CodeChunk methodChunk(String filePath, String name, String content,
                                        int startLine, int endLine) {
        return new CodeChunk(filePath, TYPE_METHOD, name, content, startLine, endLine);
    }

    /**
     * 生成送入 embedding 的文本。
     *
     * <p>在原始代码前拼一段 {@code [class:AuthService] src/.../AuthService.java} 的结构化前缀，
     * 让类型与命名信息参与向量化——纯代码文本的向量对「这个类是干什么的」表达偏弱，
     * 带上类型与路径能显著改善自然语言查询的召回。
     */
    public String toEmbeddingText() {
        return "[" + chunkType + ":" + name + "] " + filePath + "\n" + content;
    }

    /** 人类可读的位置串，形如 {@code src/main/java/Foo.java:42-78}。 */
    public String location() {
        return filePath + ":" + startLine + "-" + endLine;
    }
}
