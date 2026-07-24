package com.agentcli.mcp.prompt;

import com.agentcli.mcp.protocol.McpPromptDescriptor;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Produces the virtual {@code get_prompt} tool descriptor that exposes an MCP server's
 * prompt templates to the LLM. A single tool is registered per server; the model supplies
 * the prompt name (and optional arguments), and {@link McpPromptBridge} calls
 * {@code prompts/get} on demand.
 */
public final class McpPromptTool {
    public static final String GET_PROMPT = "get_prompt";

    private McpPromptTool() {
    }

    public static List<McpToolDescriptor> descriptors(String serverName, List<McpPromptDescriptor> prompts) {
        StringBuilder available = new StringBuilder();
        if (prompts != null && !prompts.isEmpty()) {
            available.append(" Available prompts: ");
            boolean first = true;
            for (McpPromptDescriptor p : prompts) {
                if (!first) available.append(", ");
                available.append(p.name());
                first = false;
            }
        }
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode promptName = props.putObject("promptName");
        promptName.put("type", "string");
        promptName.put("description", "Name of the prompt template to retrieve" + available);
        ObjectNode argumentsNode = props.putObject("arguments");
        argumentsNode.put("type", "object");
        argumentsNode.put("description", "Optional key-value arguments to fill into the prompt template");
        schema.putArray("required").add("promptName");
        return List.of(new McpToolDescriptor(
                serverName, GET_PROMPT,
                McpToolDescriptor.namespaced(serverName, GET_PROMPT),
                "Retrieve and instantiate a prompt template from MCP server \"" + serverName + "\"."
                        + available + ". Requires 'promptName'.",
                schema,
                McpToolDescriptor.Kind.PROMPT
        ));
    }
}
