package com.agentcli.mcp.resources;

import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Produces the two virtual tool descriptors that expose an MCP server's resources to the
 * LLM: {@code list_resources} and {@code read_resource}. Both are registered into the
 * {@link com.agentcli.tool.ToolRegistry} like ordinary MCP tools, so the model calls them
 * uniformly via {@link McpResourceBridge}.
 */
public final class McpResourceTool {
    public static final String LIST_RESOURCES = "list_resources";
    public static final String READ_RESOURCE = "read_resource";

    private McpResourceTool() {
    }

    public static List<McpToolDescriptor> descriptors(String serverName) {
        return List.of(
                new McpToolDescriptor(serverName, LIST_RESOURCES,
                        McpToolDescriptor.namespaced(serverName, LIST_RESOURCES),
                        "List resources available from MCP server \"" + serverName + "\". "
                                + "Returns URIs, names and descriptions so you can read them with "
                                + McpToolDescriptor.namespaced(serverName, READ_RESOURCE) + ".",
                        emptyObjectSchema(),
                        McpToolDescriptor.Kind.RESOURCE),
                new McpToolDescriptor(serverName, READ_RESOURCE,
                        McpToolDescriptor.namespaced(serverName, READ_RESOURCE),
                        "Read the content of a resource from MCP server \"" + serverName + "\" by URI. "
                                + "Requires the 'uri' argument obtained from "
                                + McpToolDescriptor.namespaced(serverName, LIST_RESOURCES) + ".",
                        readResourceSchema(),
                        McpToolDescriptor.Kind.RESOURCE)
        );
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static ObjectNode readResourceSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode uri = props.putObject("uri");
        uri.put("type", "string");
        uri.put("description", "Resource URI to read, e.g. file:///path or sqlite:///db/table");
        schema.putArray("required").add("uri");
        return schema;
    }
}
