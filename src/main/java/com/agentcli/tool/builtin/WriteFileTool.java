package com.agentcli.tool.builtin;

import com.agentcli.common.PolicyException;
import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 写入文件内容，受 {@link com.agentcli.common.PathGuard} 限制在项目根之内。
 *
 * 单文件 5MB 上限：LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
 * 超过即拒，避免磁盘灌满与误覆盖。
 */
public class WriteFileTool extends AbstractTool {

    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;

    private final ToolContext context;

    public WriteFileTool(ToolContext context) {
        super(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限，覆盖现有文件需 confirm=true）",
                ToolSchema.object(
                        ToolSchema.required("path", "string", "文件路径"),
                        ToolSchema.required("content", "string", "文件内容"),
                        ToolSchema.optional("confirm", "string",
                                "覆盖现有文件时的二次确认开关，传 \"true\" 或 \"yes\" 才允许覆盖")
                ));
        this.context = context;
    }

    @Override
    public String execute(Map<String, String> args) {
        String path = args.get("path");
        String content = args.get("content") == null ? "" : args.get("content");
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_WRITE_FILE_BYTES) {
            throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                    + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
        }
        Path safe = context.pathGuard().resolveSafe(path);
        if (context.sandboxPolicy().isWriteProtected(safe)) {
            throw new PolicyException("禁止写入受保护目录: " + path
                    + "（沙箱策略禁止修改受保护子目录，如 .git）");
        }
        if (Files.exists(safe) && context.sandboxPolicy().requireWriteConfirmation()) {
            String confirm = args.get("confirm");
            boolean acknowledged = "true".equalsIgnoreCase(confirm) || "yes".equalsIgnoreCase(confirm);
            if (!acknowledged) {
                throw new PolicyException("写操作需二次确认：将覆盖现有文件 " + path
                        + "，请显式传入 confirm=true 以确认覆盖");
            }
        }
        try {
            Path parent = safe.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(safe, content);
            return "文件已写入: " + path;
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }
}
