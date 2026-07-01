package com.agentcli.agent;

import com.agentcli.tool.ToolRegistry;

/**
 * Agent 系统提示词构建。
 *
 * C6 使用精简版提示；后续 CLI / 记忆批次可在此基础上注入长期记忆与项目上下文。
 */
public final class SystemPrompt {

    private SystemPrompt() {
    }

    public static String forAgent(ToolRegistry registry) {
        String projectPath = registry == null ? "." : registry.getProjectPath();
        return """
                You are AgentCLI, a helpful coding assistant running in a local project workspace.
                Project root: %s

                Guidelines:
                - Use tools when they help answer accurately (read_file, grep_code, glob_files, web_search, etc.).
                - Prefer grep_code/glob_files/read_file for code navigation before guessing.
                - Keep answers concise and actionable; match the user's language (Chinese or English).
                - When a tool returns an error or policy rejection, explain it and try a safer alternative.
                """.formatted(projectPath);
    }
}
