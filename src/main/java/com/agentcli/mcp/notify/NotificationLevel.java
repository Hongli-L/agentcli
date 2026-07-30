package com.agentcli.mcp.notify;

import java.util.Locale;

/**
 * server→client 通知级别枚举。集中维护字符串 -> 枚举映射，便于 dispatcher 解析。
 */
public enum NotificationLevel {
    ERROR,
    WARNING,
    INFO,
    DEBUG,
    LOG,
    PROGRESS;

    /**
     * 由 MCP level 字符串（window/logMessage 等）映射到枚举。
     * error -> ERROR；warning / warn -> WARNING；info -> INFO；debug -> DEBUG；
     * log -> LOG；缺省（含 null / 未知）-> INFO。
     */
    public static NotificationLevel fromString(String s) {
        if (s == null) {
            return INFO;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "error" -> ERROR;
            case "warning", "warn" -> WARNING;
            case "info" -> INFO;
            case "debug" -> DEBUG;
            case "log" -> LOG;
            default -> INFO;
        };
    }

    /** 用于对齐的定宽标签（即枚举名）。 */
    public String label() {
        return name();
    }
}
