package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.model.Position;
import com.agentcli.lsp.model.Range;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspCommandHandlerTest {

    static class FakeManager extends LspServerManager {
        private final Map<String, LspServer.Status> statuses = new HashMap<>();
        private boolean available = true;
        private Diagnostic canned;

        FakeManager(LspConfig cfg) {
            super(cfg);
        }

        void setStatus(String lang, LspServer.Status s) {
            statuses.put(lang, s);
        }

        void setAvailable(boolean a) {
            this.available = a;
        }

        void setCanned(Diagnostic d) {
            this.canned = d;
        }

        @Override
        public LspServer.Status status(String languageId) {
            return statuses.getOrDefault(languageId, LspServer.Status.STOPPED);
        }

        @Override
        public void start(String languageId) {
            statuses.put(languageId, LspServer.Status.READY);
        }

        @Override
        public void start(String languageId, String overrideConfigPath) {
            statuses.put(languageId, LspServer.Status.READY);
        }

        @Override
        public void stop(String languageId) {
            statuses.put(languageId, LspServer.Status.STOPPED);
        }

        @Override
        public void stopAll() {
            statuses.replaceAll((k, v) -> LspServer.Status.STOPPED);
        }

        @Override
        public boolean isAvailable(String languageId) {
            return available && statuses.getOrDefault(languageId, LspServer.Status.STOPPED) == LspServer.Status.READY;
        }

        @Override
        public List<Diagnostic> diagnoseFileFull(String languageId, String uri, String text, long timeoutMs) {
            return canned == null ? List.of() : List.of(canned);
        }

        @Override
        public List<Diagnostic> getAllDiagnostics() {
            return canned == null ? List.of() : List.of(canned);
        }
    }

    private LspCommandHandler handlerWith(FakeManager mgr, LspConfig cfg) {
        return new LspCommandHandler(mgr, cfg, new LspDiagnosticsFormatter());
    }

    @Test
    void startPrintsReady() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp start");
        assertTrue(out.contains("READY"), out);
    }

    @Test
    void startWithLangArg() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("lsp start --lang java");
        assertTrue(out.contains("READY"), out);
    }

    @Test
    void stopPrintsStopped() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp stop");
        assertTrue(out.contains("已停止全部"), out);
    }

    @Test
    void statusShowsState() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        mgr.setStatus("java", LspServer.Status.READY);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp status");
        assertTrue(out.contains("java"), out);
        assertTrue(out.contains("READY"), out);
        assertTrue(out.contains("autoFeedback"), out);
    }

    @Test
    void configSetAutoFeedback() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp config set autoFeedback false");
        assertTrue(out.contains("autoFeedback = false"), out);
        assertFalse(cfg.isAutoFeedback());
    }

    @Test
    void configSetFeedbackScope() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp config set feedbackScope all");
        assertTrue(out.contains("feedbackScope = all"), out);
        assertTrue("all".equals(cfg.feedbackScope()));
    }

    @Test
    void diagnosticsShowsAllSeverities() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        mgr.setStatus("java", LspServer.Status.READY);
        mgr.setAvailable(true);
        mgr.setCanned(new Diagnostic("file:///a.java", DiagnosticSeverity.INFO,
                new Range(new Position(0, 0), new Position(0, 1)), "1", "fake", "info-msg"));
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp diagnostics");
        assertTrue(out.contains("ℹ️"), "应展示 info 图标（全级别）：" + out);
        assertTrue(out.contains("info-msg"), out);
    }

    @Test
    void diagnosticsUnavailableFile() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        mgr.setAvailable(false);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp diagnostics /some/App.java");
        assertTrue(out.contains("语言服务器不可用"), out);
    }

    @Test
    void unknownSubcommandShowsUsage() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp bogus");
        assertTrue(out.contains("/lsp start"), out);
    }

    @Test
    void startWithBadConfigPathFails() {
        LspConfig cfg = new LspConfig();
        FakeManager mgr = new FakeManager(cfg);
        LspCommandHandler h = handlerWith(mgr, cfg);
        String out = h.handle("/lsp start --config /no/such/file.json");
        assertTrue(out.contains("无法加载配置"), out);
    }
}
