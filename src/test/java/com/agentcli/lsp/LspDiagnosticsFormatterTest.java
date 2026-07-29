package com.agentcli.lsp;

import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.model.Position;
import com.agentcli.lsp.model.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspDiagnosticsFormatterTest {

    private static Diagnostic d(String uri, DiagnosticSeverity s, int line, String msg) {
        return new Diagnostic(uri, s, new Range(new Position(line, 2), new Position(line, 6)),
                "1", "fake", msg);
    }

    @Test
    void displayShowsIconFileLineColumnSeverity() {
        LspDiagnosticsFormatter f = new LspDiagnosticsFormatter();
        String out = f.formatDisplay(List.of(
                d("file:///proj/A.java", DiagnosticSeverity.ERROR, 2, "错误A")));
        assertTrue(out.contains("❌"), "应含错误图标");
        assertTrue(out.contains("proj/A.java:3:3"), "行号应为 1 起：" + out);
        assertTrue(out.contains("ERROR"), out);
        assertTrue(out.contains("[fake]"), out);
        assertTrue(out.contains("错误A"), out);
    }

    @Test
    void displaySortsBySeverityDescendingAndAggregates() {
        LspDiagnosticsFormatter f = new LspDiagnosticsFormatter();
        String out = f.formatDisplay(List.of(
                d("file:///a.java", DiagnosticSeverity.HINT, 1, "h"),
                d("file:///b.java", DiagnosticSeverity.ERROR, 1, "e"),
                d("file:///c.java", DiagnosticSeverity.WARNING, 1, "w")));
        int ei = out.indexOf("e");
        int wi = out.indexOf("w");
        int hi = out.indexOf("h");
        assertTrue(ei < wi && wi < hi, "应按严重度降序聚合：" + out);
    }

    @Test
    void displayEmpty() {
        LspDiagnosticsFormatter f = new LspDiagnosticsFormatter();
        assertEquals("（无诊断）", f.formatDisplay(List.of()));
    }

    @Test
    void injectionOnlyErrorAndWarning() {
        LspDiagnosticsFormatter f = new LspDiagnosticsFormatter();
        String out = f.formatInjection("file:///a.java", List.of(
                d("file:///a.java", DiagnosticSeverity.ERROR, 1, "e"),
                d("file:///a.java", DiagnosticSeverity.INFO, 1, "i"),
                d("file:///a.java", DiagnosticSeverity.WARNING, 2, "w")));
        assertTrue(out.contains("[LSP Diagnostics] file:///a.java"));
        assertTrue(out.contains("ERROR"));
        assertTrue(out.contains("WARNING"));
        assertFalse(out.contains("INFO"), "注入块不应含 INFO：" + out);
        assertTrue(out.contains("请基于以上诊断修正代码后再继续。"));
        assertTrue(out.contains("---"));
    }

    @Test
    void injectionTruncatesOverBudget() {
        LspDiagnosticsFormatter f = new LspDiagnosticsFormatter();
        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longMsg.append("诊断内容很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长");
        }
        Diagnostic big = new Diagnostic("file:///a.java", DiagnosticSeverity.ERROR,
                new Range(new Position(0, 0), new Position(0, 3)), "1", "fake", longMsg.toString());
        String out = f.formatInjection("file:///a.java", List.of(big));
        assertTrue(out.length() <= LspDiagnosticsFormatter.MAX_INJECTION_CHARS + 200,
                "应控制在预算附近：" + out.length());
        assertTrue(out.contains("已按严重度截断"), out);
    }

    @Test
    void injectionEmptyProducesBlock() {
        LspDiagnosticsFormatter f = new LspDiagnosticsFormatter();
        String out = f.formatInjection("file:///a.java", List.of());
        assertTrue(out.contains("[LSP Diagnostics]"));
        assertTrue(out.contains("请基于以上诊断修正代码后再继续。"));
    }
}
