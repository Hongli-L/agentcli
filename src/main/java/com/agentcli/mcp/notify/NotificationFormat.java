package com.agentcli.mcp.notify;

import com.agentcli.common.AnsiStyle;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code [mcp:server]} 前缀格式、级别着色、去抖窗口与默认启用级别常量。
 */
public final class NotificationFormat {
    private NotificationFormat() {
    }

    /** {@code [mcp:<serverName>]} 前缀（printf 风格，参数 = serverName）。 */
    public static final String PREFIX = "[mcp:%s]";

    /** PROGRESS 去抖窗口（毫秒）。 */
    public static final int DEBOUNCE_WINDOW_MS = 200;

    /** 默认启用级别：全部显示。 */
    public static final Set<NotificationLevel> DEFAULT_ENABLED = EnumSet.allOf(NotificationLevel.class);

    /**
     * 渲染单行：{@code <prefix> <LEVEL>  <message>}，级别着色复用 {@link AnsiStyle}。
     *
     * <p>当 {@link AnsiStyle#isEnabled()} 为 false 时，着色方法自动原样返回，无 ANSI 噪声。
     */
    public static String format(NotificationRecord record) {
        String prefix = String.format(PREFIX, record.serverName() == null ? "" : record.serverName());
        String levelLabel = colorLevel(record.level(), record.level().label());
        String message = record.message() == null ? "" : record.message();
        return prefix + " " + levelLabel + "  " + message;
    }

    private static String colorLevel(NotificationLevel level, String label) {
        return switch (level) {
            case ERROR -> AnsiStyle.error(label);
            case WARNING -> AnsiStyle.codeLabel(label);
            case INFO -> AnsiStyle.quotePrefix(label);
            case DEBUG -> AnsiStyle.thinking(label);
            case LOG -> AnsiStyle.subtle(label);
            case PROGRESS -> AnsiStyle.subtle(label);
        };
    }
}
