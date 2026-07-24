package com.agentcli.mcp;

import com.agentcli.mcp.prompt.McpPromptBridge;
import com.agentcli.mcp.prompt.McpPromptTool;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.mcp.resources.McpResourceBridge;
import com.agentcli.mcp.resources.McpResourceCache;
import com.agentcli.mcp.resources.McpResourceDescriptor;
import com.agentcli.mcp.resources.McpResourceTool;
import com.agentcli.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the MCP resources/prompts protocol surface and the resource/prompt virtual-tool
 * bridges. A {@link FakeTransport} stands in for a real server (no network), so the test
 * exercises {@link McpClient} JSON-RPC response parsing plus the {@link McpResourceBridge} /
 * {@link McpPromptBridge} execution paths deterministically.
 */
public class McpResourcesPromptsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpClient clientWith(Map<String, JsonNode> responses) {
        return new McpClient("test", new FakeTransport(responses));
    }

    @Test
    void capabilitiesAreDetectedFromInitialize() throws Exception {
        McpClient client = clientWith(Map.of(
                "initialize", resultOf("{\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{\"tools\":{},\"resources\":{},\"prompts\":{}}}")));
        client.initialize();
        assertTrue(client.supportsResources());
        assertTrue(client.supportsPrompts());
    }

    @Test
    void toolsAreListedAsToolKind() throws Exception {
        McpClient client = clientWith(Map.of(
                "initialize", resultOf("{\"capabilities\":{\"tools\":{}}}"),
                "tools/list", resultOf("{\"tools\":[{\"name\":\"echo\","
                        + "\"description\":\"echo tool\",\"inputSchema\":{\"type\":\"object\"}}]}")));
        client.initialize();
        List<McpToolDescriptor> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertEquals(McpToolDescriptor.Kind.TOOL, tools.get(0).kind());
    }

    @Test
    void resourceBridgeListsAndReads() throws Exception {
        McpClient client = clientWith(Map.of(
                "initialize", resultOf("{\"capabilities\":{\"resources\":{}}}"),
                "resources/list", resultOf("{\"resources\":[{\"uri\":\"file:///a\","
                        + "\"name\":\"A\",\"description\":\"res a\"}]}"),
                "resources/read", resultOf("{\"contents\":[{\"uri\":\"file:///a\","
                        + "\"text\":\"hello-content\"}]}")));
        client.initialize();

        McpToolDescriptor listDesc = new McpToolDescriptor("test", "list_resources",
                "mcp__test__list_resources", "list", JsonNodeFactory.instance.objectNode(),
                McpToolDescriptor.Kind.RESOURCE);
        String listed = new McpResourceBridge(listDesc, client).execute(Map.of());
        assertTrue(listed.contains("file:///a"), "list_resources surfaces the URI");

        McpToolDescriptor readDesc = new McpToolDescriptor("test", "read_resource",
                "mcp__test__read_resource", "read", JsonNodeFactory.instance.objectNode(),
                McpToolDescriptor.Kind.RESOURCE);
        String read = new McpResourceBridge(readDesc, client).execute(Map.of("uri", "file:///a"));
        assertTrue(read.contains("hello-content"), "read_resource returns resource text");
    }

    @Test
    void promptBridgeGetsPrompt() throws Exception {
        McpClient client = clientWith(Map.of(
                "initialize", resultOf("{\"capabilities\":{\"prompts\":{}}}"),
                "prompts/get", resultOf("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":{\"type\":\"text\",\"text\":\"please review\"}}]}")));
        client.initialize();

        McpToolDescriptor desc = new McpToolDescriptor("test", "get_prompt",
                "mcp__test__get_prompt", "get", JsonNodeFactory.instance.objectNode(),
                McpToolDescriptor.Kind.PROMPT);
        String result = new McpPromptBridge(desc, client).execute(Map.of("promptName", "reviewer"));
        assertTrue(result.contains("please review"), "get_prompt returns rendered prompt");
    }

    @Test
    void promptListReturnsDescriptors() throws Exception {
        McpClient client = clientWith(Map.of(
                "initialize", resultOf("{\"capabilities\":{\"prompts\":{}}}"),
                "prompts/list", resultOf("{\"prompts\":[{\"name\":\"reviewer\","
                        + "\"description\":\"review prompt\",\"arguments\":[]}]}")));
        client.initialize();
        List<?> prompts = client.listPrompts();
        assertEquals(1, prompts.size());
    }

    @Test
    void missingUriReportsErrorWithoutTouchingClient() {
        McpToolDescriptor readDesc = new McpToolDescriptor("test", "read_resource",
                "mcp__test__read_resource", "read", JsonNodeFactory.instance.objectNode(),
                McpToolDescriptor.Kind.RESOURCE);
        // null client is safe: the missing-argument guard returns before any network call
        String result = new McpResourceBridge(readDesc, null).execute(Map.of());
        assertTrue(result.contains("uri"), "missing 'uri' should be reported");
    }

    @Test
    void resourceToolDescriptorsAreNamespacedAndTyped() {
        List<McpToolDescriptor> descs = McpResourceTool.descriptors("fs");
        assertEquals(2, descs.size());
        assertTrue(descs.stream().allMatch(d -> d.kind() == McpToolDescriptor.Kind.RESOURCE));
        assertEquals("mcp__fs__list_resources", descs.get(0).namespacedName());
        assertEquals("mcp__fs__read_resource", descs.get(1).namespacedName());
        assertTrue(descs.get(1).inputSchema().path("required").toString().contains("uri"));
    }

    @Test
    void promptToolDescriptorIsNamespacedAndTyped() {
        List<McpToolDescriptor> descs = McpPromptTool.descriptors("fs", List.of());
        assertEquals(1, descs.size());
        McpToolDescriptor d = descs.get(0);
        assertEquals(McpToolDescriptor.Kind.PROMPT, d.kind());
        assertEquals("mcp__fs__get_prompt", d.namespacedName());
        assertTrue(d.inputSchema().path("required").toString().contains("promptName"));
    }

    @Test
    void resourceCacheUsesLazyInvalidation() {
        McpResourceCache cache = new McpResourceCache();
        cache.put("fs", List.of(
                new McpResourceDescriptor("fs", "file:///a", "A", "", "d", "text/plain", 10L)));
        assertEquals(1, cache.get("fs").size());
        assertEquals(1, cache.all().size());
        cache.invalidateServer("fs");
        assertTrue(cache.isServerStale("fs"));
        assertEquals(0, cache.get("fs").size()); // stale server returns empty until refreshed
        assertFalse(cache.all().contains(new McpResourceDescriptor("fs", "file:///a", "A", "", "d", "text/plain", 10L)));
    }

    private static JsonNode resultOf(String json) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("result", (JsonNode) MAPPER.valueToTree(parseObject(json)));
        return root;
    }

    private static Object parseObject(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** In-memory transport that answers preset JSON-RPC results without any network I/O. */
    static class FakeTransport implements McpTransport {
        private final Map<String, JsonNode> responses;
        private final List<Consumer<JsonNode>> listeners = new ArrayList<>();

        FakeTransport(Map<String, JsonNode> responses) {
            this.responses = responses;
        }

        @Override
        public void send(JsonNode message) {
            String method = message.path("method").asText();
            JsonNode preset = responses.get(method);
            if (preset == null || !preset.has("result")) {
                return; // notifications / unknown methods are ignored
            }
            ObjectNode out = (ObjectNode) preset.deepCopy();
            out.put("jsonrpc", "2.0");
            out.put("id", message.path("id").asLong());
            for (Consumer<JsonNode> listener : listeners) {
                listener.accept(out);
            }
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
            if (listener != null) listeners.add(listener);
        }

        @Override
        public String transportName() {
            return "fake";
        }

        @Override
        public void close() {
        }
    }
}
