package com.agentcli.mcp.prompt;

import com.agentcli.mcp.McpClient;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Bridges the {@code get_prompt} virtual tool to the {@link Tool} interface.
 */
public class McpPromptBridge implements Tool {
    private final McpToolDescriptor descriptor;
    private final McpClient client;

    public McpPromptBridge(McpToolDescriptor descriptor, McpClient client) {
        this.descriptor = descriptor;
        this.client = client;
    }

    @Override
    public String name() {
        return descriptor.namespacedName();
    }

    @Override
    public String description() {
        return descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP prompt tool: " + descriptor.serverName() + "/" + descriptor.name()
                : descriptor.description();
    }

    @Override
    public JsonNode parameters() {
        return descriptor.inputSchema();
    }

    @Override
    public String execute(Map<String, String> args) {
        try {
            String promptName = args == null ? null : args.get("promptName");
            if (promptName == null || promptName.isBlank()) {
                return "MCP prompt error: missing required argument 'promptName'";
            }
            String argumentsJson = args.get("arguments");
            if (argumentsJson == null || argumentsJson.isBlank()) {
                argumentsJson = "{}";
            }
            return client.getPrompt(promptName, argumentsJson);
        } catch (Exception e) {
            return "MCP prompt error (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage();
        }
    }
}
