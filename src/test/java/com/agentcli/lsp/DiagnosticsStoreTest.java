package com.agentcli.lsp;

import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.model.Position;
import com.agentcli.lsp.model.Range;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsStoreTest {

    private static Diagnostic diag(String uri, DiagnosticSeverity severity, int line) {
        return new Diagnostic(uri, severity,
                new Range(new Position(line, 0), new Position(line, 5)), "1", "fake", "msg");
    }

    @Test
    void updateAndGet() {
        DiagnosticsStore store = new DiagnosticsStore();
        store.update("file:///a.java", List.of(diag("file:///a.java", DiagnosticSeverity.ERROR, 1)));
        assertEquals(1, store.get("file:///a.java").size());
        assertEquals(0, store.get("file:///missing").size());
    }

    @Test
    void awaitCompletesOnUpdate() throws Exception {
        DiagnosticsStore store = new DiagnosticsStore();
        store.registerAwait("file:///a.java");
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                // ignore
            }
            store.update("file:///a.java", List.of(diag("file:///a.java", DiagnosticSeverity.WARNING, 2)));
        }).start();
        assertTrue(store.await("file:///a.java", 3000));
        assertEquals(1, store.get("file:///a.java").size());
    }

    @Test
    void awaitTimesOut() throws Exception {
        DiagnosticsStore store = new DiagnosticsStore();
        store.registerAwait("file:///x.java");
        assertFalse(store.await("file:///x.java", 200), "应当在超时后返回 false");
    }

    @Test
    void filterBySeverityAndSortDesc() {
        DiagnosticsStore store = new DiagnosticsStore();
        store.update("u", List.of(
                diag("u", DiagnosticSeverity.HINT, 4),
                diag("u", DiagnosticSeverity.ERROR, 1),
                diag("u", DiagnosticSeverity.WARNING, 3)));
        List<Diagnostic> filtered = store.filterBySeverity(DiagnosticSeverity.WARNING);
        assertEquals(2, filtered.size());
        assertEquals(DiagnosticSeverity.ERROR, filtered.get(0).severity());
        assertEquals(DiagnosticSeverity.WARNING, filtered.get(1).severity());
    }

    @Test
    void clearRemovesAll() {
        DiagnosticsStore store = new DiagnosticsStore();
        store.update("u", List.of(diag("u", DiagnosticSeverity.ERROR, 1)));
        store.clear();
        assertTrue(store.getAll().isEmpty());
    }
}
