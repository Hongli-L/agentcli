package com.agentcli.agent;

import com.agentcli.tool.ToolRegistry;

/**
 * Agent 系统提示词构建。
 */
public final class SystemPrompt {

    private SystemPrompt() {
    }

    public static String forAgent(ToolRegistry registry) {
        return forAgent(registry, "");
    }

    public static String forAgent(ToolRegistry registry, String memoryContext) {
        String projectPath = registry == null ? "." : registry.getProjectPath();
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are AgentCLI, a helpful coding assistant running in a local project workspace.
                Project root: %s

                Guidelines:
                - Use tools when they help answer accurately (read_file, grep_code, glob_files, web_search, save_memory, etc.).
                - Prefer grep_code/glob_files/read_file for code navigation before guessing.
                - Use save_memory only when the user explicitly asks to remember a stable fact or preference.
                - Keep answers concise and actionable; match the user's language (Chinese or English).
                - When a tool returns an error or policy rejection, explain it and try a safer alternative.
                """.formatted(projectPath));
        if (memoryContext != null && !memoryContext.isBlank()) {
            prompt.append("\n").append(memoryContext.trim()).append("\n");
        }
        return prompt.toString().trim();
    }
}
