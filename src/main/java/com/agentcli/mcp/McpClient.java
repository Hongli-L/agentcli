package com.agentcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.agentcli.mcp.jsonrpc.JsonRpcClient;
import com.agentcli.mcp.protocol.McpCallToolRequest;
import com.agentcli.mcp.protocol.McpCallToolResult;
import com.agentcli.mcp.protocol.McpInitializeRequest;
import com.agentcli.mcp.protocol.McpSchemaSanitizer;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
    }

    public void initialize() throws IOException {
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(),
                initializeTimeoutSeconds());
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode()
                : result.path("capabilities");
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    private int initializeTimeoutSeconds() {
        String configured = System.getProperty("agentcli.mcp.initialize.timeout.seconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("AGENTCLI_MCP_INITIALIZE_TIMEOUT_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) continue;
            String description = tool.path("description").asText("");
            JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));
            descriptors.add(new McpToolDescriptor(
                    serverName, name,
                    McpToolDescriptor.namespaced(serverName, name),
                    description, schema));
        }
        return descriptors;
    }

    public String callTool(String toolName, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);
        JsonNode result = rpc.request("tools/call", params, 60);
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
        String text = callResult.toText();
        if (callResult.isError()) {
            return "MCP tool error: " + text;
        }
        return text;
    }

    public List<String> stderrLines() { return transport.stderrLines(); }
    public Long processId() { return transport.processId(); }
    public String transportName() { return transport.transportName(); }

    @Override
    public void close() {
        rpc.close();
    }
}
