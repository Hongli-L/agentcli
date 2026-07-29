package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.config.LspServerConfig;
import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.testutil.LspTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspServerManagerTest {

    @TempDir
    Path tmp;

    private LspServerConfig javaCfg(String diagJson, String... extra) {
        return LspTestSupport.fakeJavaServer(tmp.toString(), diagJson, extra);
    }

    private static String uriOf(Path file) {
        return "file:///" + file.toAbsolutePath().toString().replace('\\', '/');
    }

    @Test
    void startReadyAndDiagnose() throws Exception {
        LspConfig cfg = new LspConfig();
        cfg.setServer("java", javaCfg(LspTestSupport.fooErrorDiagnostics()));
        LspServerManager mgr = new LspServerManager(cfg);
        mgr.start("java");
        assertEquals(LspServer.Status.READY, mgr.status("java"));

        Path file = tmp.resolve("App.java");
        Files.writeString(file, "public class A { int x = foo; }");
        String uri = uriOf(file);
        List<Diagnostic> diags = mgr.diagnoseFile("java", uri, Files.readString(file), 5000L);
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.ERROR, diags.get(0).severity());
        mgr.stopAll();
    }

    @Test
    void stopSetsStopped() {
        LspConfig cfg = new LspConfig();
        cfg.setServer("java", javaCfg(LspTestSupport.fooErrorDiagnostics()));
        LspServerManager mgr = new LspServerManager(cfg);
        mgr.start("java");
        mgr.stop("java");
        assertEquals(LspServer.Status.STOPPED, mgr.status("java"));
    }

    @Test
    void crashMarksError() throws Exception {
        LspConfig cfg = new LspConfig();
        cfg.setServer("java", javaCfg(null, "--crash-after", "2"));
        LspServerManager mgr = new LspServerManager(cfg);
        mgr.start("java");
        assertEquals(LspServer.Status.READY, mgr.status("java"));
        // 进程应已退出并触发 onExit 降级
        Thread.sleep(600);
        assertEquals(LspServer.Status.ERROR, mgr.status("java"));
        mgr.stopAll();
    }

    @Test
    void timeoutReturnsEmptyGracefully() throws Exception {
        LspConfig cfg = new LspConfig();
        cfg.setServer("java", javaCfg(null, "--hang"));
        LspServerManager mgr = new LspServerManager(cfg);
        mgr.start("java");
        Path file = tmp.resolve("B.java");
        Files.writeString(file, "anything");
        String uri = uriOf(file);
        long start = System.currentTimeMillis();
        List<Diagnostic> diags = mgr.diagnoseFile("java", uri, "anything", 600L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5000, "超时不应过长：" + elapsed);
        assertTrue(diags.isEmpty(), "超时应降级为空");
        mgr.stopAll();
    }

    @Test
    void capabilityUnsupportedReturnsEmptyFast() throws Exception {
        LspConfig cfg = new LspConfig();
        cfg.setServer("java", javaCfg(null, "--no-diagnostics"));
        LspServerManager mgr = new LspServerManager(cfg);
        mgr.start("java");
        Path file = tmp.resolve("C.java");
        Files.writeString(file, "anything");
        String uri = uriOf(file);
        long start = System.currentTimeMillis();
        List<Diagnostic> diags = mgr.diagnoseFile("java", uri, "anything", 5000L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1000, "能力不支持应尽快返回：" + elapsed);
        assertTrue(diags.isEmpty());
        mgr.stopAll();
    }

    @Test
    void unavailableLanguageReturnsEmpty() {
        LspConfig cfg = new LspConfig();
        cfg.servers().remove("java");
        LspServerManager mgr = new LspServerManager(cfg);
        List<Diagnostic> diags = mgr.diagnoseFile("java", "file:///x.java", "x", 1000L);
        assertTrue(diags.isEmpty());
        assertFalse(mgr.isAvailable("java"));
    }
}
