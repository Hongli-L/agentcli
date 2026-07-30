package com.agentcli.mcp.notify;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 终端通知展示：级别过滤 + PROGRESS 去抖（200ms）+ 经注入的 printer 打印。
 *
 * <p>线程安全：printer 的同步 / 刷新由接线层（{@code ReplReader.printAsync}）保证；
 * 本类仅用 {@link ConcurrentHashMap} 维护去抖时间戳，行为无副作用。
 */
public class TerminalNotificationPresenter implements NotificationPresenter {

    private final Consumer<String> printer;
    private final Set<NotificationLevel> enabled;
    private final long debounceWindowMs;
    private final Map<String, Long> lastProgressEmit = new ConcurrentHashMap<>();

    public TerminalNotificationPresenter(Consumer<String> printer) {
        this(printer, NotificationFormat.DEFAULT_ENABLED, NotificationFormat.DEBOUNCE_WINDOW_MS);
    }

    public TerminalNotificationPresenter(Consumer<String> printer,
                                         Set<NotificationLevel> enabled,
                                         long debounceWindowMs) {
        this.printer = printer;
        this.enabled = enabled == null ? NotificationFormat.DEFAULT_ENABLED : enabled;
        this.debounceWindowMs = debounceWindowMs;
    }

    @Override
    public void show(NotificationRecord record) {
        if (record == null || printer == null) {
            return;
        }
        if (!enabled.contains(record.level())) {
            return;
        }
        if (record.level() == NotificationLevel.PROGRESS) {
            long now = System.currentTimeMillis();
            String key = record.serverName() == null ? "" : record.serverName();
            Long last = lastProgressEmit.get(key);
            if (last != null && (now - last) < debounceWindowMs) {
                return; // 去抖：窗口内丢弃
            }
            lastProgressEmit.put(key, now);
        }
        printer.accept(NotificationFormat.format(record));
    }
}
