package com.agentcli.cli;

import com.agentcli.common.EnvLoader;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 交互式控制台字符集：Windows 下默认编码常与 UTF-8 不一致，需与终端实际编码对齐。
 */
final class ConsoleCharsets {

    private ConsoleCharsets() {
    }

    static Charset forConsole() {
        String configured = EnvLoader.get("AGENTCLI_CONSOLE_CHARSET");
        if (configured != null && !configured.isBlank()) {
            return Charset.forName(configured.trim());
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Charset.defaultCharset();
        }
        return StandardCharsets.UTF_8;
    }
}
