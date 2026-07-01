package com.agentcli.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动参数解析。
 */
final class CliArgs {

    final boolean help;
    final boolean version;
    final String prompt;
    final String provider;
    final String projectPath;

    private CliArgs(boolean help, boolean version, String prompt, String provider, String projectPath) {
        this.help = help;
        this.version = version;
        this.prompt = prompt;
        this.provider = provider;
        this.projectPath = projectPath;
    }

    static CliArgs parse(String[] args) {
        boolean help = false;
        boolean version = false;
        String prompt = null;
        String provider = null;
        String projectPath = null;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg) || "--help".equals(arg)) {
                    help = true;
                } else if ("-v".equals(arg) || "--version".equals(arg)) {
                    version = true;
                } else if ("-p".equals(arg) || "--prompt".equals(arg)) {
                    prompt = readValue(args, ++i, arg);
                } else if ("--provider".equals(arg)) {
                    provider = readValue(args, ++i, arg);
                } else if ("--project".equals(arg) || "-C".equals(arg)) {
                    projectPath = readValue(args, ++i, arg);
                } else if (!arg.startsWith("-")) {
                    prompt = joinPromptTail(args, i);
                    break;
                }
            }
        }
        return new CliArgs(help, version, prompt, provider, projectPath);
    }

    /** 把剩余参数拼成单次 prompt（用于 -p 后跟多个词的场景）。 */
    static String joinPromptTail(String[] args, int fromIndex) {
        if (args == null || fromIndex >= args.length) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = fromIndex; i < args.length; i++) {
            parts.add(args[i]);
        }
        return String.join(" ", parts).trim();
    }

    private static String readValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("缺少 " + flag + " 的参数值");
        }
        return args[index];
    }

    static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return provider;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }
}
