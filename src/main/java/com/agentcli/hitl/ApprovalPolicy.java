package com.agentcli.hitl;

import java.util.Set;

/**
 * 危险操作识别策略 —— 基于静态规则判断哪些工具调用需要人工确认。
 *
 * <p>设计原则：
 * <ul>
 *   <li>只读操作（read_file / list_dir / glob_files / grep_code）无副作用，不需要确认</li>
 *   <li>写入/执行类操作（write_file / execute_command）有潜在破坏性，需要确认</li>
 *   <li>create_project 属于写入操作，默认需要确认</li>
 *   <li>MCP 工具（mcp__ 前缀）来自外部 server，默认需要确认（预留）</li>
 * </ul>
 */
public final class ApprovalPolicy {

    /** 需要人工确认的内置工具集合。 */
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project"
    );

    private ApprovalPolicy() {
    }

    /**
     * 判断该工具调用是否需要人工确认。
     */
    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    /**
     * 获取危险等级描述。
     */
    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "\uD83D\uDD34 高危";
            case "write_file" -> "\uD83D\uDFE1 中危";
            case "create_project" -> "\uD83D\uDFE1 中危";
            default -> isMcpTool(toolName) ? "\uD83D\uDFE1 MCP" : "\uD83D\uDFE2 安全";
        };
    }

    /**
     * 获取危险操作的风险说明。
     */
    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> isMcpTool(toolName)
                    ? "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务"
                    : "安全的只读操作";
        };
    }

    /**
     * 获取需要审批的工具名集合。
     */
    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    /**
     * 是否为 MCP 外部工具（以 mcp__ 前缀命名）。
     */
    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    /**
     * 从 mcp__server__tool 格式的工具名中提取 server 名。
     *
     * @return server 名，如果不是 MCP 工具则返回 null
     */
    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}
