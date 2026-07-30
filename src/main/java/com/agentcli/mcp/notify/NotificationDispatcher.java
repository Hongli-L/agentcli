package com.agentcli.mcp.notify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/**
 * 解析 server→client 通知（JSON-RPC 无 id 消息）并分发到 {@link NotificationPresenter}。
 *
 * <p>设计要点：
 * <ul>
 *   <li>实现 {@link Consumer}&lt;{@link JsonNode}&gt;，直接注册到
 *       {@code JsonRpcClient.onNotification}（零改动 McpTransport 契约）。</li>
 *   <li>method→level 映射集中在此处：{@code window/logMessage} / {@code notifications/message} /
 *       {@code notifications/progress}。</li>
 *   <li>解析异常或未知 method 一律静默忽略，绝不向上冒泡到 request/response 主流程。</li>
 * </ul>
 */
public class NotificationDispatcher implements Consumer<JsonNode> {

    private final String serverName;
    private final NotificationPresenter presenter;

    public NotificationDispatcher(String serverName, NotificationPresenter presenter) {
        this.serverName = serverName == null ? "" : serverName;
        this.presenter = presenter;
    }

    @Override
    public void accept(JsonNode notification) {
        if (notification == null || presenter == null) {
            return;
        }
        try {
            NotificationRecord record = parse(notification);
            if (record != null) {
                presenter.show(record);
            }
        } catch (Exception ignored) {
            // 解析异常静默忽略，不冒泡
        }
    }

    /**
     * 解析单条通知为 {@link NotificationRecord}；未知 method 返回 null（忽略）。
     */
    private NotificationRecord parse(JsonNode notification) {
        String method = notification.path("method").asText("");
        if (method.isBlank()) {
            return null;
        }
        JsonNode params = notification.path("params");
        return switch (method) {
            case "window/logMessage", "notifications/message" -> {
                NotificationLevel level = NotificationLevel.fromString(params.path("level").asText());
                String message = extractMessage(params);
                yield new NotificationRecord(serverName, level, method, message, null, null);
            }
            case "notifications/progress" -> {
                long progress = params.path("progress").asLong();
                Long total = params.has("total") ? params.path("total").asLong() : null;
                String message = buildProgressMessage(params, progress, total);
                yield new NotificationRecord(
                        serverName, NotificationLevel.PROGRESS, method, message, progress, total);
            }
            default -> null;
        };
    }

    private static String extractMessage(JsonNode params) {
        if (params.has("message")) {
            return params.path("message").asText("");
        }
        if (params.has("data")) {
            JsonNode data = params.path("data");
            return data.isTextual() ? data.asText() : data.toString();
        }
        return "";
    }

    private static String buildProgressMessage(JsonNode params, long progress, Long total) {
        String base = total != null ? (progress + "/" + total) : Long.toString(progress);
        String extra = params.has("message") ? params.path("message").asText("") : "";
        return extra.isBlank() ? base : base + " " + extra;
    }
}
