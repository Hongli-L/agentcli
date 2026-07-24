package com.agentcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema,
        Kind kind
) {
    public enum Kind { TOOL, RESOURCE, PROMPT }

    public McpToolDescriptor {
        if (kind == null) kind = Kind.TOOL;
    }

    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
