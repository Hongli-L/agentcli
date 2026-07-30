package com.agentcli.cli;

import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 跨会话持久化的 REPL 历史，对 {@link DefaultHistory} 做装饰式敏感过滤。
 *
 * <p>仅覆写 {@link #add(Instant, String)}：命中过滤规则的行（空行、超长、疑似密钥、长 base64）
 * 直接丢弃、不落盘；加载与持久化仍由 JLine 原生完成（绑定 {@code LineReader} 的
 * {@code HISTORY_FILE} 变量后自动 load，{@code close} 时 save）。
 *
 * <p>构造器刻意不创建 {@code ~/.agentcli} 目录，以避免在非 TTY（不构造本类）场景下产生副作用；
 * 目录仅在真正 attach 到 TTY 的 {@link LineReader} 时才惰性创建。
 */
public final class ReplHistory extends DefaultHistory {

    /** 历史文件唯一路径来源：{@code ~/.agentcli/history}。 */
    public static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".agentcli", "history");

    private static final int MAX_LINE_LENGTH = 8000;
    private static final int BASE64_MIN_LENGTH = 240;

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|token|password|secret|authorization|bearer)");
    private static final Pattern BASE64_PATTERN = Pattern.compile("[A-Za-z0-9+/=]+");

    public ReplHistory() {
        // 不创建目录，避免非 TTY 副作用
    }

    @Override
    public void attach(LineReader reader) {
        Path parent = DEFAULT_PATH.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception ignored) {
                // 目录创建失败不应阻塞 REPL 启动
            }
        }
        super.attach(reader);
    }

    @Override
    public void add(Instant time, String line) {
        if (!shouldSkip(line)) {
            super.add(time, line);
        }
    }

    /** 判断某行是否应被历史过滤（不落盘）。包级可见以便单元测试直接验证。 */
    boolean shouldSkip(String line) {
        if (line == null) {
            return true;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (line.length() > MAX_LINE_LENGTH) {
            return true;
        }
        if (SECRET_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (trimmed.length() >= BASE64_MIN_LENGTH && BASE64_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return false;
    }
}
