package com.agentcli.audit;

import com.agentcli.hitl.ApprovalPolicy;
import com.agentcli.hitl.ApprovalRequest;
import com.agentcli.hitl.ApprovalResult;
import com.agentcli.hitl.HitlHandler;
import com.agentcli.hitl.HitlToolRegistry;
import com.agentcli.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAuditTest {

    /** 一个总是拒绝的 HITL 处理器（不触碰终端输入，便于单测）。 */
    private HitlHandler rejectingHandler() {
        return new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                return ApprovalResult.reject("not allowed in test");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {
            }
        };
    }

    @Test
    void approvalDecisionIsAuditedOnReject() throws Exception {
        // 写文件是危险工具，需审批；拒绝路径不会真正执行工具
        assertTrue(ApprovalPolicy.requiresApproval("write_file"));

        Path dir = Files.createTempDirectory("agentcli-approval-test");
        SqliteStore store = SqliteStore.open(dir.resolve("agentcli-test.db"));
        AuditLog auditLog = new AuditLog(store);

        HitlToolRegistry registry = new HitlToolRegistry("/tmp", rejectingHandler(), auditLog);
        String result = registry.executeTool("write_file", "{\"path\":\"/tmp/x.txt\",\"content\":\"y\"}");

        assertTrue(result.contains("已被拒绝"), "应返回拒绝结果: " + result);

        var entries = auditLog.query(10);
        assertEquals(1, entries.size());
        assertEquals("approval", entries.get(0).kind());
        assertEquals("write_file", entries.get(0).target());
        assertTrue(entries.get(0).detailJson().contains("\"decision\":\"REJECTED\""),
                "审计详情应包含决策: " + entries.get(0).detailJson());
    }

    @Test
    void auditLogCanBeNullWithoutBreaking() {
        // 无审计时（生产降级路径）不应抛错
        HitlToolRegistry registry = new HitlToolRegistry("/tmp", rejectingHandler());
        String result = registry.executeTool("write_file", "{}");
        assertTrue(result.contains("已被拒绝"));
    }
}
