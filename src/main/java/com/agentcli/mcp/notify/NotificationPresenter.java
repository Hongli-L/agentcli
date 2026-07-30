package com.agentcli.mcp.notify;

/**
 * 通知展示接口：把解析后的 {@link NotificationRecord} 呈现给终端（或测试桩）。
 *
 * <p>实现方（如 {@link TerminalNotificationPresenter}）负责级别过滤、去抖与最终打印。
 */
public interface NotificationPresenter {
    void show(NotificationRecord record);
}
