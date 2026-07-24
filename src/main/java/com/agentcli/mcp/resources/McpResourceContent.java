package com.agentcli.mcp.resources;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single content blob returned by {@code resources/read}.
 */
public record McpResourceContent(String uri, String mimeType, String text, String blob) {
    public static McpResourceContent fromJson(JsonNode node) {
        if (node == null) return new McpResourceContent(null, null, null, null);
        String text = node.hasNonNull("text") ? node.path("text").asText("") : null;
        String blob = node.hasNonNull("blob") ? node.path("blob").asText("") : null;
        return new McpResourceContent(
                node.path("uri").asText(""),
                node.path("mimeType").asText(""),
                text,
                blob
        );
    }

    public boolean isText() {
        return text != null;
    }
}
