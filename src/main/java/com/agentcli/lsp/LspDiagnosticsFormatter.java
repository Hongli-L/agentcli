package com.agentcli.lsp;

import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断格式化器。
 * <ul>
 *     <li>① 终端可读展示（LSP-06）：severity 图标、聚合多文件、按 severity 降序；</li>
 *     <li>② 回灌块格式化（LSP-04）：仅 error+warning，预算 3000 字符，超限按严重度降序保留并截断。</li>
 * </ul>
 */
public class LspDiagnosticsFormatter {

    /** 回灌块允许的最大字符数。 */
    public static final int MAX_INJECTION_CHARS = 3000;

    private static final Map<DiagnosticSeverity, String> ICONS = new LinkedHashMap<>();

    static {
        ICONS.put(DiagnosticSeverity.ERROR, "❌");
        ICONS.put(DiagnosticSeverity.WARNING, "⚠️");
        ICONS.put(DiagnosticSeverity.INFO, "ℹ️");
        ICONS.put(DiagnosticSeverity.HINT, "💡");
    }

    private static final Comparator<Diagnostic> BY_SEVERITY_DESC =
            (a, b) -> Integer.compare(a.severity().code(), b.severity().code());

    /** @return 级别中文名 */
    public String severityLabel(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> "ERROR";
            case WARNING -> "WARNING";
            case INFO -> "INFO";
            case HINT -> "HINT";
        };
    }

    /** @return 级别图标 */
    public String severityIcon(DiagnosticSeverity severity) {
        return ICONS.getOrDefault(severity, "ℹ️");
    }

    private String formatLine(Diagnostic d) {
        String icon = severityIcon(d.severity());
        String file = displayFile(d.uri());
        int line = d.range().start().line() + 1; // 终端展示为 1 起
        int col = d.range().start().character() + 1;
        String source = (d.source() == null || d.source().isBlank()) ? "" : " [" + d.source() + "]";
        return String.format("%s %s:%d:%d %s%s %s",
                icon, file, line, col, severityLabel(d.severity()), source, d.message());
    }

    private static String displayFile(String uri) {
        if (uri == null) {
            return "";
        }
        if (uri.startsWith("file:///")) {
            return uri.substring("file:///".length());
        }
        if (uri.startsWith("file://")) {
            return uri.substring("file://".length());
        }
        return uri;
    }

    /**
     * 终端可读展示（含全级别）。聚合多文件、按 severity 降序。
     *
     * @param diagnostics 诊断列表（可为多文件，每项自带 uri）
     * @return 可读文本
     */
    public String formatDisplay(List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "（无诊断）";
        }
        List<Diagnostic> sorted = new ArrayList<>(diagnostics);
        sorted.sort(BY_SEVERITY_DESC);
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : sorted) {
            sb.append(formatLine(d)).append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 回灌块格式化（LSP-04）。仅 error+warning，预算 {@link #MAX_INJECTION_CHARS}，
     * 超限按严重度降序保留并截断，追加提示。
     *
     * @param uri        文档 URI
     * @param diagnostics 诊断列表
     * @return 回灌块文本
     */
    public String formatInjection(String uri, List<Diagnostic> diagnostics) {
        List<Diagnostic> filtered = new ArrayList<>();
        if (diagnostics != null) {
            for (Diagnostic d : diagnostics) {
                if (d.severity() == DiagnosticSeverity.ERROR || d.severity() == DiagnosticSeverity.WARNING) {
                    filtered.add(d);
                }
            }
        }
        filtered.sort(BY_SEVERITY_DESC);

        StringBuilder sb = new StringBuilder();
        sb.append("[LSP Diagnostics] ").append(uri).append(System.lineSeparator());
        int total = 0;
        int kept = 0;
        for (Diagnostic d : filtered) {
            String line = String.format("%s (%d:%d) [%s] %s",
                    severityLabel(d.severity()),
                    d.range().start().line() + 1,
                    d.range().start().character() + 1,
                    d.source() == null || d.source().isBlank() ? "" : d.source(),
                    d.message());
            int projected = total + line.length() + 1; // +1 为行分隔符
            if (projected > MAX_INJECTION_CHARS) {
                // 超出预算：截断当前行以适配剩余预算（即便它是第一条）
                int remaining = MAX_INJECTION_CHARS - total - 1;
                if (remaining > 0) {
                    int end = Math.min(line.length(), remaining);
                    line = line.substring(0, end) + "…";
                    sb.append(line).append(System.lineSeparator());
                    total += line.length() + 1;
                    kept++;
                }
                sb.append("...（诊断过长，已按严重度截断）").append(System.lineSeparator());
                break;
            }
            sb.append(line).append(System.lineSeparator());
            total += line.length() + 1;
            kept++;
        }
        sb.append("---").append(System.lineSeparator());
        sb.append("请基于以上诊断修正代码后再继续。");
        return sb.toString();
    }
}
