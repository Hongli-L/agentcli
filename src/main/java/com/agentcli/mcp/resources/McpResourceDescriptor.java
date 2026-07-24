package com.agentcli.mcp.resources;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Describes a single MCP resource exposed by a server (from {@code resources/list}).
 */
public record McpResourceDescriptor(
        String serverName,
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        Long size
) {
    public static McpResourceDescriptor fromJson(String serverName, JsonNode node) {
        if (node == null
                || node.path("uri").isMissingNode()
                || node.path("uri").asText("").isBlank()) {
            return null;
        }
        return new McpResourceDescriptor(
                serverName,
                node.path("uri").asText(""),
                node.path("name").asText(""),
                node.path("title").asText(""),
                node.path("description").asText(""),
                node.path("mimeType").asText(""),
                node.hasNonNull("size") ? node.path("size").asLong() : null
        );
    }

    public String displayName() {
        if (title != null && !title.isBlank()) return title;
        if (name != null && !name.isBlank()) return name;
        return uri;
    }
}
