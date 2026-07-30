package com.agentcli.mcp.notify;

/**
 * server→client 通知的值对象：承载来源 server、级别、原始 method、展示消息与进度信息。
 */
public record NotificationRecord(
        String serverName,
        NotificationLevel level,
        String method,
        String message,
        Long progress,
        Long total) {
}
