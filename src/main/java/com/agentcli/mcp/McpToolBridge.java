package com.agentcli.mcp;

import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges an MCP tool descriptor to the {@link Tool} interface,
 * so MCP-discovered tools are seamlessly callable through {@link com.agentcli.tool.ToolRegistry}.
 */
public class McpToolBridge implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpToolDescriptor descriptor;
    private final McpClient client;

    public McpToolBridge(McpToolDescriptor descriptor, McpClient client) {
        this.descriptor = descriptor;
        this.client = client;
    }

    @Override
    public String name() {
        return descriptor.namespacedName();
    }

    @Override
    public String description() {
        String desc = descriptor.description();
        return desc == null || desc.isBlank()
                ? "MCP tool: " + descriptor.serverName() + "/" + descriptor.name()
                : desc;
    }

    @Override
    public JsonNode parameters() {
        return descriptor.inputSchema();
    }

    @Override
    public String execute(Map<String, String> args) {
        Map<String, String> safe = args == null ? Map.of() : args;
        if (safe.isEmpty()) {
            return callSafe("{}");
        }
        Map<String, JsonNode> jsonArgs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            String value = entry.getValue();
            try {
                jsonArgs.put(entry.getKey(), MAPPER.readTree(value));
            } catch (Exception e) {
                jsonArgs.put(entry.getKey(), MAPPER.getNodeFactory().textNode(value));
            }
        }
        try {
            return callSafe(MAPPER.writeValueAsString(jsonArgs));
        } catch (Exception e) {
            return "MCP tool error (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage();
        }
    }

    private String callSafe(String argumentsJson) {
        try {
            return client.callTool(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return "MCP tool error (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage();
        }
    }
}
