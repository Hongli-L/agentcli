package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.model.Position;
import com.agentcli.lsp.model.Range;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspContextInjectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内存版管理器桩：injector 调用时返回预设诊断。 */
    static class FakeManager extends LspServerManager {
        private final Map<String, List<Diagnostic>> responses = new HashMap<>();
        private final Set<String> diagnosed = new HashSet<>();
        private boolean available = true;

        FakeManager(LspConfig cfg) {
            super(cfg);
        }

        void setResponse(String uri, List<Diagnostic> diags) {
            responses.put(uri, diags);
        }

        void setAvailable(boolean available) {
            this.available = available;
        }

        Set<String> diagnosed() {
            return diagnosed;
        }

        @Override
        public boolean isAvailable(String languageId) {
            return available && "java".equals(languageId);
        }

        @Override
        public List<Diagnostic> diagnoseFile(String languageId, String uri, String text, long timeoutMs) {
            diagnosed.add(uri);
            return responses.getOrDefault(uri, List.of());
        }
    }

    @TempDir
    Path tmp;

    private static String uriOf(Path file) {
        return "file:///" + file.toAbsolutePath().toString().replace('\\', '/');
    }

    private ToolExecutionResult writeResult(String path, String content) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("path", path);
        n.put("content", content);
        return new ToolExecutionResult("write_file", "write_file", n.toString(), "ok", 0, false);
    }

    private Diagnostic errorDiag(String uri) {
        return new Diagnostic(uri, DiagnosticSeverity.ERROR,
                new Range(new Position(0, 0), new Position(0, 3)), "1", "fake", "boom");
    }

    @Test
    void detectsWriteFileAndInjects() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        Path file = tmp.resolve("App.java");
        String uri = uriOf(file);
        mgr.setResponse(uri, List.of(errorDiag(uri)));

        LspContextInjector inj = new LspContextInjector(
                mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

        List<ToolExecutionResult> out = inj.afterToolResults(List.of(writeResult(file.toString(), "x")));
        assertEquals(1, out.size());
        assertTrue(out.get(0).result().contains("ok"), "应保留原结果");
        assertTrue(out.get(0).result().contains("[LSP Diagnostics]"), out.get(0).result());
        assertTrue(out.get(0).result().contains("boom"));
    }

    @Test
    void debounceWithin300msSkipsSecond() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        Path file = tmp.resolve("Deb.java");
        String uri = uriOf(file);
        mgr.setResponse(uri, List.of(errorDiag(uri)));

        LspContextInjector inj = new LspContextInjector(
                mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

        List<ToolExecutionResult> first = inj.afterToolResults(List.of(writeResult(file.toString(), "x")));
        assertTrue(first.get(0).result().contains("[LSP Diagnostics]"), "首次应注入");

        List<ToolExecutionResult> second = inj.afterToolResults(List.of(writeResult(file.toString(), "y")));
        assertFalse(second.get(0).result().contains("[LSP Diagnostics]"), "300ms 内应去抖跳过");
    }

    @Test
    void singleRoundSingleFileInjectedOnce() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        Path file = tmp.resolve("Once.java");
        String uri = uriOf(file);
        mgr.setResponse(uri, List.of(errorDiag(uri)));

        LspContextInjector inj = new LspContextInjector(
                mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

        // 同一轮、同一文件出现两次 write_file
        List<ToolExecutionResult> out = inj.afterToolResults(List.of(
                writeResult(file.toString(), "x"),
                writeResult(file.toString(), "y")));
        long injected = out.stream().filter(r -> r.result().contains("[LSP Diagnostics]")).count();
        assertEquals(1, injected, "单轮单文件最多注入 1 次");
    }

    @Test
    void autoFeedbackFalseSkips() {
        LspConfig cfg = new LspConfig();
        cfg.setAutoFeedback(false);
        FakeManager mgr = new FakeManager(cfg);
        Path file = tmp.resolve("Off.java");
        String uri = uriOf(file);
        mgr.setResponse(uri, List.of(errorDiag(uri)));

        LspContextInjector inj = new LspContextInjector(
                mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

        List<ToolExecutionResult> out = inj.afterToolResults(List.of(writeResult(file.toString(), "x")));
        assertFalse(out.get(0).result().contains("[LSP Diagnostics]"), "autoFeedback 关闭应跳过");
    }

    @Test
    void noServerNoOp() {
        LspConfig cfg = new LspConfig();
        cfg.servers().remove("java");
        FakeManager mgr = new FakeManager(cfg);
        mgr.setAvailable(false);
        Path file = tmp.resolve("NoSrv.java");
        String uri = uriOf(file);
        mgr.setResponse(uri, List.of(errorDiag(uri)));

        LspContextInjector inj = new LspContextInjector(
                mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

        List<ToolExecutionResult> out = inj.afterToolResults(List.of(writeResult(file.toString(), "x")));
        assertFalse(out.get(0).result().contains("[LSP Diagnostics]"), "无可用 server 应空操作");
        assertTrue(mgr.diagnosed().isEmpty(), "不应调用诊断");
    }

    @Test
    void nonJavaExtensionNotInjected() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        Path file = tmp.resolve("note.txt");
        String uri = uriOf(file);
        mgr.setResponse(uri, List.of(errorDiag(uri)));

        LspContextInjector inj = new LspContextInjector(
                mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

        List<ToolExecutionResult> out = inj.afterToolResults(List.of(writeResult(file.toString(), "x")));
        assertFalse(out.get(0).result().contains("[LSP Diagnostics]"), "非 Java 文件不应注入");
    }

    @Test
    void nullSafeAndPreservesOriginalOnError() {
        LspConfig cfg = new LspConfig();
        LspContextInjector inj = new LspContextInjector(
                null, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());
        // manager 为 null 时不抛异常
        List<ToolExecutionResult> out = inj.afterToolResults(List.of(writeResult("x.java", "x")));
        assertEquals("ok", out.get(0).result());

        // 空列表也安全
        assertTrue(inj.afterToolResults(List.of()).isEmpty());
    }
}
