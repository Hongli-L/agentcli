package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.model.Diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * REPL 的 {@code /lsp} 命令处理器。支持：
 * <ul>
 *     <li>{@code start [--lang java] [--config <path>]}</li>
 *     <li>{@code stop [--lang java]}</li>
 *     <li>{@code status [--lang java]}</li>
 *     <li>{@code diagnostics [<file>]}（别名 {@code diag}，展示全级别）</li>
 *     <li>{@code config set <key> <value>}</li>
 * </ul>
 */
public class LspCommandHandler {

    private final LspServerManager manager;
    private final LspConfig config;
    private final LspDiagnosticsFormatter formatter;
    private final String projectDir;

    public LspCommandHandler(LspServerManager manager,
                             LspConfig config,
                             LspDiagnosticsFormatter formatter) {
        this(manager, config, formatter, config == null ? "." : config.projectDir());
    }

    public LspCommandHandler(LspServerManager manager,
                             LspConfig config,
                             LspDiagnosticsFormatter formatter,
                             String projectDir) {
        this.manager = manager;
        this.config = config;
        this.formatter = formatter;
        this.projectDir = (projectDir == null || projectDir.isBlank()) ? "." : projectDir;
    }

    /**
     * 处理 {@code /lsp ...} 命令（前导 {@code /} 可有可无）。
     *
     * @param raw 原始命令文本
     * @return 可读的输出文本
     */
    public String handle(String raw) {
        if (raw == null) {
            return usage();
        }
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        List<String> tokens = tokenize(s);
        if (tokens.isEmpty() || !tokens.get(0).equalsIgnoreCase("lsp")) {
            return usage();
        }
        if (tokens.size() < 2) {
            return usage();
        }
        String sub = tokens.get(1).toLowerCase();
        List<String> args = tokens.subList(2, tokens.size());

        return switch (sub) {
            case "start" -> handleStart(args);
            case "stop" -> handleStop(args);
            case "status" -> handleStatus(args);
            case "diagnostics", "diag" -> handleDiagnostics(args);
            case "config" -> handleConfig(args);
            default -> usage();
        };
    }

    private String handleStart(List<String> args) {
        String lang = "java";
        String configPath = null;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equalsIgnoreCase("--lang") && i + 1 < args.size()) {
                lang = args.get(++i);
            } else if (args.get(i).equalsIgnoreCase("--config") && i + 1 < args.size()) {
                configPath = args.get(++i);
            }
        }
        if (configPath != null && !Files.exists(Path.of(configPath))) {
            return "✗ 无法加载配置：" + configPath;
        }
        try {
            manager.start(lang, configPath);
        } catch (Exception e) {
            return "✗ 启动 " + lang + " 失败：" + e.getMessage();
        }
        LspServer.Status status = manager.status(lang);
        if (status == LspServer.Status.READY) {
            return "✓ 已启动 " + lang + " 语言服务器 (READY)";
        }
        LspServer server = manager.server(lang);
        String err = server != null && server.lastError() != null ? server.lastError() : "未知错误";
        return "✗ 启动 " + lang + " 失败：" + err;
    }

    private String handleStop(List<String> args) {
        String lang = null;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equalsIgnoreCase("--lang") && i + 1 < args.size()) {
                lang = args.get(++i);
            }
        }
        if (lang == null) {
            manager.stopAll();
            return "✓ 已停止全部语言服务器";
        }
        manager.stop(lang);
        return "✓ 已停止 " + lang + " 语言服务器";
    }

    private String handleStatus(List<String> args) {
        String lang = null;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equalsIgnoreCase("--lang") && i + 1 < args.size()) {
                lang = args.get(++i);
            }
        }
        List<String> langs = new ArrayList<>();
        if (lang != null) {
            langs.add(lang);
        } else {
            langs.addAll(config.servers().keySet());
        }
        if (langs.isEmpty()) {
            return "（未配置任何语言服务器）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LSP 状态：").append(System.lineSeparator());
        for (String l : langs) {
            LspServer.Status st = manager.status(l);
            sb.append("  ").append(l).append(": ").append(st);
            if (st == LspServer.Status.ERROR) {
                LspServer s = manager.server(l);
                if (s != null && s.lastError() != null) {
                    sb.append(" - ").append(s.lastError());
                }
            }
            sb.append(System.lineSeparator());
        }
        sb.append("  autoFeedback: ").append(config.isAutoFeedback())
                .append("  feedbackScope: ").append(config.feedbackScope());
        return sb.toString();
    }

    private String handleDiagnostics(List<String> args) {
        if (!args.isEmpty()) {
            String fileArg = args.get(0);
            Path path = Path.of(fileArg);
            String languageId = new LspLanguageDetector().languageIdForPath(fileArg);
            if (languageId == null) {
                return "✗ 无法识别文件语言：" + fileArg;
            }
            if (!manager.isAvailable(languageId)) {
                return "✗ 语言服务器不可用：" + languageId + "（请先 /lsp start）";
            }
            String uri = toUri(path.toAbsolutePath().toString());
            String text = "";
            try {
                text = Files.readString(path);
            } catch (Exception ignored) {
                // 文件可能不存在，仍尝试诊断
            }
            List<Diagnostic> diags = manager.diagnoseFileFull(languageId, uri, text, 5000L);
            return formatter.formatDisplay(diags);
        }
        // 全量展示
        List<Diagnostic> all = manager.getAllDiagnostics();
        return formatter.formatDisplay(all);
    }

    private String handleConfig(List<String> args) {
        if (args.size() < 3 || !args.get(0).equalsIgnoreCase("set")) {
            return "用法：/lsp config set <autoFeedback|feedbackScope> <value>";
        }
        String key = args.get(1).toLowerCase();
        String value = args.get(2);
        return switch (key) {
            case "autofeedback" -> {
                boolean on = Boolean.parseBoolean(value) || "1".equals(value) || "on".equalsIgnoreCase(value);
                config.setAutoFeedback(on);
                yield "✓ autoFeedback = " + on;
            }
            case "feedbackscope" -> {
                config.setFeedbackScope(value);
                yield "✓ feedbackScope = " + config.feedbackScope();
            }
            default -> "✗ 未知配置项：" + key;
        };
    }

    private String usage() {
        return """
                /lsp 命令用法：
                  /lsp start [--lang java] [--config <path>]   启动语言服务器
                  /lsp stop [--lang java]                       停止（缺省停止全部）
                  /lsp status [--lang java]                     查看状态
                  /lsp diagnostics [<file>]  (别名 diag)        查看诊断（全级别）
                  /lsp config set <autoFeedback|feedbackScope> <value>
                """;
    }

    private static String toUri(String absolutePath) {
        String p = absolutePath.replace('\\', '/');
        if (p.startsWith("/")) {
            return "file://" + p;
        }
        return "file:///" + p;
    }

    /** 简易分词，支持双引号包裹的路径。 */
    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }
}
