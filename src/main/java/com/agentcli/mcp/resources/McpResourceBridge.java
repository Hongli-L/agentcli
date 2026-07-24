package com.agentcli.mcp.resources;

import com.agentcli.mcp.McpClient;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Bridges the two resource virtual tools ({@code list_resources} / {@code read_resource})
 * to the {@link Tool} interface so they flow through the normal {@link com.agentcli.tool.ToolRegistry}.
 */
public class McpResourceBridge implements Tool {
    private final McpToolDescriptor descriptor;
    private final McpClient client;

    public McpResourceBridge(McpToolDescriptor descriptor, McpClient client) {
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
                ? "MCP resource tool: " + descriptor.serverName() + "/" + descriptor.name()
                : descriptor.description();
    }

    @Override
    public JsonNode parameters() {
        return descriptor.inputSchema();
    }

    @Override
    public String execute(Map<String, String> args) {
        try {
            if (McpResourceTool.LIST_RESOURCES.equals(descriptor.name())) {
                return McpClient.formatResources(client.listResources());
            }
            if (McpResourceTool.READ_RESOURCE.equals(descriptor.name())) {
                String uri = args == null ? null : args.get("uri");
                if (uri == null || uri.isBlank()) {
                    return "MCP resource error: missing required argument 'uri'";
                }
                return McpClient.formatResourceContents(client.readResource(uri));
            }
            return "MCP resource error: unknown resource tool " + descriptor.name();
        } catch (Exception e) {
            return "MCP resource error (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage();
        }
    }
}
