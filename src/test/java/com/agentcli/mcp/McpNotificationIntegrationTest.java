package com.agentcli.mcp;

import com.agentcli.mcp.notify.NotificationFormat;
import com.agentcli.mcp.notify.NotificationLevel;
import com.agentcli.mcp.notify.NotificationPresenter;
import com.agentcli.mcp.notify.NotificationRecord;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：McpClient 注册监听器后，FakeMcpTransport 推送的通知能被 presenter 收到；
 * 主流程 request/response（callTool）不受影响；server 不推送通知时 callTool 零异常、参数不变（降级）。
 */
public class McpNotificationIntegrationTest {

    @TempDir Path projectDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static class CapturingPresenter implements NotificationPresenter {
        final List<NotificationRecord> records = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public void show(NotificationRecord record) {
            records.add(record);
            lines.add(NotificationFormat.format(record));
        }
    }

    @Test
    void notificationPushedByServerIsDeliveredToPresenter() throws Exception {
        FakeMcpTransport transport = new FakeMcpTransport(Map.of(
                "initialize", resultOf("{\"capabilities\":{}}")));
        CapturingPresenter presenter = new CapturingPresenter();
        McpClient client = new McpClient("github", transport, projectDir, presenter);
        client.initialize();

        transport.pushNotification("window/logMessage",
                MAPPER.readTree("{\"level\":\"info\",\"message\":\"rate limit remaining: 4999\"}"));

        assertEquals(1, presenter.records.size());
        NotificationRecord r = presenter.records.get(0);
        assertEquals(NotificationLevel.INFO, r.level());
        assertEquals("rate limit remaining: 4999", r.message());
        assertEquals("github", r.serverName());
        assertTrue(presenter.lines.get(0).contains("[mcp:github]"), presenter.lines.get(0));
    }

    @Test
    void callToolWithoutNotificationIsExceptionFreeAndUnchanged() throws Exception {
        FakeMcpTransport transport = new FakeMcpTransport(Map.of(
                "initialize", resultOf("{\"capabilities\":{}}"),
                "tools/call", resultOf(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"TOOL_OK\"}],\"isError\":false}")));
        CapturingPresenter presenter = new CapturingPresenter();
        McpClient client = new McpClient("github", transport, projectDir, presenter);
        client.initialize();

        String result = client.callTool("fs_read", "{\"query\":\"plain\"}");
        assertTrue(result.contains("TOOL_OK"), result);
        // 未推送任何通知 -> 无记录
        assertTrue(presenter.records.isEmpty());
        String args = transport.lastToolCallArguments();
        assertTrue(args.contains("plain"), args);
        assertFalse(args.contains("<file"), args);
    }

    @Test
    void callToolInjectsMentionAndNotificationChannelCoexists() throws Exception {
        Files.writeString(projectDir.resolve("README.md"), "doc-text");
        FakeMcpTransport transport = new FakeMcpTransport(Map.of(
                "initialize", resultOf("{\"capabilities\":{}}"),
                "tools/call", resultOf(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"isError\":false}")));
        CapturingPresenter presenter = new CapturingPresenter();
        McpClient client = new McpClient("github", transport, projectDir, presenter);
        client.initialize();

        String result = client.callTool("fs_read", "{\"query\":\"@README.md\"}");
        assertTrue(result.contains("OK"), result);
        String args = transport.lastToolCallArguments();
        assertTrue(args.contains("<file path="), args);
        assertTrue(args.contains("README.md"), args);
        assertTrue(args.contains("doc-text"), args);

        // 通知通道独立可用
        transport.pushNotification("notifications/progress",
                MAPPER.readTree("{\"progress\":3,\"total\":10}"));
        assertEquals(1, presenter.records.size());
        assertEquals(NotificationLevel.PROGRESS, presenter.records.get(0).level());
    }

    @Test
    void registerNotificationListenerReceivesRawNotification() throws Exception {
        FakeMcpTransport transport = new FakeMcpTransport(Map.of(
                "initialize", resultOf("{\"capabilities\":{}}")));
        List<String> captured = new ArrayList<>();
        McpClient client = new McpClient("github", transport, projectDir, null);
        client.initialize();
        client.registerNotificationListener(n ->
                captured.add(n.path("method").asText()));

        transport.pushNotification("notifications/message",
                MAPPER.readTree("{\"level\":\"info\",\"data\":\"x\"}"));
        assertEquals(1, captured.size());
        assertEquals("notifications/message", captured.get(0));
    }
}
