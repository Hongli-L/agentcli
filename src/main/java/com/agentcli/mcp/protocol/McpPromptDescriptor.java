package com.agentcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Describes a single MCP prompt template exposed by a server (from {@code prompts/list}).
 */
public record McpPromptDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String title,
        String description,
        JsonNode arguments
) {
    public static String namespaced(String serverName, String promptName) {
        return "mcp__" + serverName + "__" + promptName;
    }
}
