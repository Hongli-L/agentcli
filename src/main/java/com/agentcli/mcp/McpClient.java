package com.agentcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.agentcli.mcp.jsonrpc.JsonRpcClient;
import com.agentcli.mcp.jsonrpc.JsonRpcException;
import com.agentcli.mcp.protocol.McpCallToolRequest;
import com.agentcli.mcp.protocol.McpCallToolResult;
import com.agentcli.mcp.protocol.McpInitializeRequest;
import com.agentcli.mcp.protocol.McpPromptDescriptor;
import com.agentcli.mcp.protocol.McpSchemaSanitizer;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.mcp.resources.McpResourceContent;
import com.agentcli.mcp.resources.McpResourceDescriptor;
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
                    description, schema, McpToolDescriptor.Kind.TOOL));
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

    public boolean supportsResources() {
        return serverCapabilities != null && serverCapabilities.has("resources");
    }

    public boolean supportsPrompts() {
        return serverCapabilities != null && serverCapabilities.has("prompts");
    }

    public List<McpResourceDescriptor> listResources() throws IOException {
        try {
            JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) return List.of();
            List<McpResourceDescriptor> descriptors = new ArrayList<>();
            for (JsonNode node : resources) {
                McpResourceDescriptor d = McpResourceDescriptor.fromJson(serverName, node);
                if (d != null) descriptors.add(d);
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) return List.of();
            throw e;
        }
    }

    public List<McpResourceContent> readResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        JsonNode result = rpc.request("resources/read", params, 60);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) return List.of();
        List<McpResourceContent> list = new ArrayList<>();
        for (JsonNode node : contents) {
            list.add(McpResourceContent.fromJson(node));
        }
        return list;
    }

    public void subscribeResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        try {
            rpc.request("resources/subscribe", params, 30);
        } catch (JsonRpcException e) {
            if (e.code() == -32601) return;
            throw e;
        }
    }

    public List<McpPromptDescriptor> listPrompts() throws IOException {
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) return List.of();
            List<McpPromptDescriptor> descriptors = new ArrayList<>();
            for (JsonNode node : prompts) {
                String name = node.path("name").asText("");
                if (name.isBlank()) continue;
                descriptors.add(new McpPromptDescriptor(
                        serverName, name,
                        McpPromptDescriptor.namespaced(serverName, name),
                        node.path("title").asText(""),
                        node.path("description").asText(""),
                        node.path("arguments")));
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) return List.of();
            throw e;
        }
    }

    public String getPrompt(String name, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", name);
        if (args != null && args.isObject() && args.size() > 0) {
            params.set("arguments", args);
        }
        JsonNode result = rpc.request("prompts/get", params, 60);
        JsonNode messages = result.path("messages");
        if (!messages.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode message : messages) {
            String role = message.path("role").asText("");
            JsonNode content = message.path("content");
            String text = content.path("text").asText("");
            String resourceUri = content.path("resource").path("uri").asText("");
            if (!text.isBlank()) {
                sb.append("[").append(role).append("]\n").append(text).append("\n\n");
            } else if (!resourceUri.isBlank()) {
                sb.append("[").append(role).append("] resource: ").append(resourceUri).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) return "No resources available.";
        StringBuilder sb = new StringBuilder();
        for (McpResourceDescriptor r : resources) {
            sb.append("- uri: ").append(r.uri()).append("\n");
            sb.append("  name: ").append(r.displayName()).append("\n");
            if (r.description() != null && !r.description().isBlank()) {
                sb.append("  description: ").append(r.description()).append("\n");
            }
            if (r.mimeType() != null && !r.mimeType().isBlank()) {
                sb.append("  mimeType: ").append(r.mimeType()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) return "Resource returned no content.";
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent c : contents) {
            if (c.isText()) {
                sb.append(c.text());
            } else if (c.blob() != null && !c.blob().isBlank()) {
                sb.append("<resource uri=\"").append(c.uri()).append("\" mimeType=\"")
                        .append(c.mimeType()).append("\">\n[base64 blob, omitted]\n</resource>");
            }
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    public List<String> stderrLines() { return transport.stderrLines(); }
    public Long processId() { return transport.processId(); }
    public String transportName() { return transport.transportName(); }

    @Override
    public void close() {
        rpc.close();
    }
}
