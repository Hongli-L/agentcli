package com.agentcli.lsp;

import com.agentcli.lsp.config.LspServerConfig;
import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.testutil.LspTestSupport;
import com.agentcli.lsp.transport.LspStdioTransport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspClientTest {

    private static String uriOf(Path file) {
        return "file:///" + file.toAbsolutePath().toString().replace('\\', '/');
    }

    @Test
    void initializeAndReceivePublishDiagnostics() throws Exception {
        LspServerConfig cfg = LspTestSupport.fakeJavaServer(
                System.getProperty("java.io.tmpdir"), LspTestSupport.fooErrorDiagnostics());
        LspStdioTransport t = new LspStdioTransport(cfg.command(), cfg.args(), cfg.env(), null);
        DiagnosticsStore store = new DiagnosticsStore();
        LspClient client = new LspClient(t, store);

        ObjectNode caps = client.newObject();
        caps.putObject("textDocument").putObject("publishDiagnostics");
        ObjectNode ci = client.newObject();
        ci.put("name", "test");
        ci.put("version", "1.0");
        client.initialize("file:///tmp", caps, ci, null);
        assertNotNull(client.serverCapabilities(), "应保存 serverCapabilities");

        Path file = Path.of(System.getProperty("java.io.tmpdir"), "AppClientTest.java");
        String uri = uriOf(file);
        String text = "public class A { int x = foo; }";
        store.registerAwait(uri);
        client.didOpen(uri, "java", text);

        assertTrue(store.await(uri, 5000), "应收到 publishDiagnostics");
        List<Diagnostic> diags = store.get(uri);
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.ERROR, diags.get(0).severity());
        assertTrue(diags.get(0).message().contains("foo"));

        client.shutdown();
        client.exit();
        t.close();
    }
}
