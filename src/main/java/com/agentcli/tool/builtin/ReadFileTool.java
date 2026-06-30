package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取文件内容，受 {@link com.agentcli.common.PathGuard} 限制在项目根之内。
 * 支持 offset/limit 按行读取，避免把大文件整段塞进上下文。
 */
public class ReadFileTool extends AbstractTool {

    private static final int MAX_READ_FILE_LINES = 2_000;

    private final ToolContext context;

    public ReadFileTool(ToolContext context) {
        super(
                "read_file",
                "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文",
                ToolSchema.object(
                        ToolSchema.required("path", "string", "文件路径"),
                        ToolSchema.optional("offset", "integer", "起始行号，1 表示第一行；省略时读取全文"),
                        ToolSchema.optional("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行")
                ));
        this.context = context;
    }

    @Override
    public String execute(Map<String, String> args) {
        Path safe = context.pathGuard().resolveSafe(args.get("path"));
        try {
            return readFile(safe, args);
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    private String readFile(Path file, Map<String, String> args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "读取文件失败: 不是普通文件";
        }
        boolean ranged = args.containsKey("offset") || args.containsKey("limit");
        if (!ranged) {
            return "文件内容:\n" + Files.readString(file);
        }

        int offset = Math.max(1, ToolArgs.parseInt(args, "offset", 1));
        int limit = Math.max(1, Math.min(ToolArgs.parseInt(args, "limit", 200), MAX_READ_FILE_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围";
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return sb.toString().trim();
    }
}
