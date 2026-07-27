package com.agentcli.audit;

import com.agentcli.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    private SqliteStore newStore() throws Exception {
        Path dir = Files.createTempDirectory("agentcli-audit-test");
        return SqliteStore.open(dir.resolve("agentcli-test.db"));
    }

    @Test
    void recordsLlmAndApprovalThenReadsBack() throws Exception {
        SqliteStore store = newStore();
        AuditLog log = new AuditLog(store);

        log.recordLlm("glm-4", 120, 30, 0, 850, "let me think...", true);
        log.recordApproval("write_file", "REJECTED", " risky", "{\"path\":\"/x\"}");

        var all = log.query(10);
        assertEquals(2, all.size());
        // 倒序：最近写入的 approval 在前
        assertEquals("approval", all.get(0).kind());
        assertEquals("write_file", all.get(0).target());
        assertEquals("llm", all.get(1).kind());
        assertEquals(150, all.get(1).tokens());
        assertTrue(all.get(1).latencyMs() >= 0);
    }

    @Test
    void queryByKindFilters() throws Exception {
        SqliteStore store = newStore();
        AuditLog log = new AuditLog(store);

        log.recordLlm("glm-4", 10, 5, 0, 100, null, false);
        log.recordLlm("glm-4", 20, 8, 0, 120, null, false);
        log.recordApproval("execute_command", "APPROVED", null, "ls");

        var llms = log.query(10, "llm");
        assertEquals(2, llms.size());
        assertTrue(llms.stream().allMatch(e -> "llm".equals(e.kind())));

        var approvals = log.query(10, "approval");
        assertEquals(1, approvals.size());
        assertEquals("execute_command", approvals.get(0).target());
    }

    @Test
    void emptyQueryReturnsNothing() throws Exception {
        AuditLog log = new AuditLog(newStore());
        assertTrue(log.query(10).isEmpty());
    }
}
