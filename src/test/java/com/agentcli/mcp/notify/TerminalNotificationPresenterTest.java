package com.agentcli.mcp.notify;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link TerminalNotificationPresenter} 的 [mcp:server] 前缀格式、级别过滤与 PROGRESS 去抖。
 */
public class TerminalNotificationPresenterTest {

    private final List<String> lines = new ArrayList<>();
    private final Consumer<String> printer = lines::add;

    private static NotificationRecord rec(String server, NotificationLevel level, String msg) {
        return new NotificationRecord(server, level, "window/logMessage", msg, null, null);
    }

    private static NotificationRecord progress(String server, long p, Long total, String msg) {
        return new NotificationRecord(server, NotificationLevel.PROGRESS, "notifications/progress", msg, p, total);
    }

    @Test
    void printsFormattedLineWithPrefix() {
        TerminalNotificationPresenter p = new TerminalNotificationPresenter(printer);
        p.show(rec("test", NotificationLevel.INFO, "hello from server"));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("[mcp:test]"), lines.get(0));
        assertTrue(lines.get(0).contains("hello from server"), lines.get(0));
    }

    @Test
    void filtersDisabledLevels() {
        TerminalNotificationPresenter p = new TerminalNotificationPresenter(
                printer, EnumSet.of(NotificationLevel.INFO), NotificationFormat.DEBOUNCE_WINDOW_MS);
        p.show(rec("test", NotificationLevel.ERROR, "boom"));
        p.show(rec("test", NotificationLevel.INFO, "ok"));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("ok"), lines.get(0));
    }

    @Test
    void debouncesProgressWithinWindow() {
        // 大窗口确保 3 次 progress 全部落在窗口内 -> 合并为 1 行
        TerminalNotificationPresenter p = new TerminalNotificationPresenter(
                printer, NotificationFormat.DEFAULT_ENABLED, 10_000);
        p.show(progress("test", 1L, 10L, "1/10"));
        p.show(progress("test", 2L, 10L, "2/10"));
        p.show(progress("test", 3L, 10L, "3/10"));
        assertEquals(1, lines.size());
    }

    @Test
    void emitsProgressAfterWindowExpires() throws Exception {
        TerminalNotificationPresenter p = new TerminalNotificationPresenter(
                printer, NotificationFormat.DEFAULT_ENABLED, 50);
        p.show(progress("test", 1L, 10L, "1/10"));
        Thread.sleep(90);
        p.show(progress("test", 2L, 10L, "2/10"));
        assertEquals(2, lines.size());
    }
}
