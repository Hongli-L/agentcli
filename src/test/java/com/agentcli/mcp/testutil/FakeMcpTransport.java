package com.agentcli.mcp.testutil;

import com.agentcli.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 顶层 {@link McpTransport} 桩：记录 {@code tools/call} 的 argumentsJson，并提供
 * {@link #pushNotification} 模拟 server→client 通知。不依赖真实 server。
 *
 * <p>与 {@code McpResourcesPromptsTest} 内嵌的 {@code FakeTransport} 互不冲突：本类独立成文件、
 * 置于 {@code testutil} 包，且仅增加“记录 tools/call”与“主动推送通知”两项能力。
 */
public class FakeMcpTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, JsonNode> responses;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final List<String> recordedToolCalls = new CopyOnWriteArrayList<>();

    public FakeMcpTransport(Map<String, JsonNode> responses) {
        this.responses = responses == null ? Map.of() : responses;
    }

    /** 返回最近一次 tools/call 的 argumentsJson（未调用过则返回 null）。 */
    public String lastToolCallArguments() {
        return recordedToolCalls.isEmpty() ? null : recordedToolCalls.get(recordedToolCalls.size() - 1);
    }

    /** 返回全部 tools/call 的 argumentsJson（按调用顺序）。 */
    public List<String> recordedToolCalls() {
        return List.copyOf(recordedToolCalls);
    }

    /** 模拟 server→client 推通知：构造无 id 消息并交付给所有 onReceive 监听器。 */
    public void pushNotification(String method, JsonNode params) {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params == null ? MAPPER.createObjectNode() : params);
        for (Consumer<JsonNode> listener : listeners) {
            listener.accept(notification);
        }
    }

    @Override
    public void send(JsonNode message) {
        String method = message.path("method").asText();
        if ("tools/call".equals(method)) {
            recordedToolCalls.add(message.path("params").path("arguments").toString());
        }
        JsonNode preset = responses.get(method);
        if (preset == null || !preset.has("result")) {
            return; // notifications / 未知方法忽略
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
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "fake";
    }

    @Override
    public void close() {
        // no-op for in-memory transport
    }
}
