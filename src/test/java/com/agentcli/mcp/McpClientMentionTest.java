package com.agentcli.mcp;

import com.agentcli.mcp.mention.MentionResolver;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.mcp.testutil.FakeMcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 @mention 经 {@link McpClient#callTool} 注入 tools/call 的 argumentsJson：
 * 含 @file 的调用参数携带文件内容、不含 mention 时原文不变，且经 {@link McpToolBridge} 传入
 * 含 @path 参数也能解析（P1-3）。
 */
public class McpClientMentionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path projectDir;

    private static JsonNode resultOf(String json) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("result", (JsonNode) MAPPER.valueToTree(parse(json)));
        return root;
    }

    private static Object parse(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, JsonNode> responses() {
        return Map.of(
                "initialize", resultOf("{\"capabilities\":{}}"),
                "tools/call", resultOf(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"TOOL_OK\"}],\"isError\":false}"));
    }

    @Test
    void injectsFileContentIntoToolCallArguments() throws Exception {
        Files.writeString(projectDir.resolve("README.md"), "readme body");
        FakeMcpTransport transport = new FakeMcpTransport(responses());
        McpClient client = new McpClient("test", transport, projectDir, null);
        client.initialize();
        client.callTool("fs_read", "{\"query\":\"@README.md\"}");
        String args = transport.lastToolCallArguments();
        assertTrue(args.contains("<file path="), args);
        assertTrue(args.contains("README.md"), args);
        assertTrue(args.contains("readme body"), args);
    }

    @Test
    void leavesArgumentsUnchangedWhenNoMention() throws Exception {
        FakeMcpTransport transport = new FakeMcpTransport(responses());
        McpClient client = new McpClient("test", transport, projectDir, null);
        client.initialize();
        client.callTool("fs_read", "{\"query\":\"plain text\"}");
        String args = transport.lastToolCallArguments();
        assertTrue(args.contains("plain text"), args);
        assertFalse(args.contains("<file"), args);
    }

    @Test
    void injectsViaMcpToolBridgeExecute() throws Exception {
        // P1-3：McpToolBridge.execute 最终调用 callTool -> resolveJson 自动覆盖
        Path appFile = projectDir.resolve("src/main/App.java");
        Files.createDirectories(appFile.getParent());
        Files.writeString(appFile, "class App {}");
        FakeMcpTransport transport = new FakeMcpTransport(responses());
        McpClient client = new McpClient("test", transport, projectDir, null);
        client.initialize();
        McpToolDescriptor desc = new McpToolDescriptor(
                "test", "fs_read", "mcp__test__fs_read",
                "read", JsonNodeFactory.instance.objectNode(), McpToolDescriptor.Kind.TOOL);
        McpToolBridge bridge = new McpToolBridge(desc, client);
        bridge.execute(Map.of("query", "\"@src/main/App.java\""));
        String args = transport.lastToolCallArguments();
        assertTrue(args.contains("<file path="), args);
        assertTrue(args.contains("src/main/App.java"), args);
        assertTrue(args.contains("class App {}"), args);
    }

    @Test
    void nullProjectDirKeepsClientFullyDegraded() throws Exception {
        // 无 projectDir 时 MentionResolver 为 null，callTool 不应解析 @mention，也不抛异常
        Files.writeString(projectDir.resolve("README.md"), "x");
        FakeMcpTransport transport = new FakeMcpTransport(responses());
        McpClient client = new McpClient("test", transport, null, null);
        client.initialize();
        client.callTool("fs_read", "{\"query\":\"@README.md\"}");
        String args = transport.lastToolCallArguments();
        assertFalse(args.contains("<file"), args);
        assertTrue(args.contains("@README.md"), args);
    }
}
