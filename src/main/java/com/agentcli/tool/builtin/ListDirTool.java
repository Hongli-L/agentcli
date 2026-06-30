package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * 列出目录内容，受 {@link com.agentcli.common.PathGuard} 限制在项目根之内。
 */
public class ListDirTool extends AbstractTool {

    private final ToolContext context;

    public ListDirTool(ToolContext context) {
        super(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                ToolSchema.object(ToolSchema.required("path", "string", "目录路径")));
        this.context = context;
    }

    @Override
    public String execute(Map<String, String> args) {
        Path safe = context.pathGuard().resolveSafe(args.get("path"));
        try {
            File[] files = safe.toFile().listFiles();
            if (files == null) {
                return "目录为空或不存在";
            }
            StringBuilder sb = new StringBuilder("目录内容:\n");
            for (File f : files) {
                sb.append(f.isDirectory() ? "[D] " : "[F] ")
                        .append(f.getName())
                        .append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "列出目录失败: " + e.getMessage();
        }
    }
}
