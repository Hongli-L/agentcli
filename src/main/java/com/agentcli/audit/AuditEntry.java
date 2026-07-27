package com.agentcli.audit;

import java.time.Instant;

/**
 * 一条审计记录，对应 {@code audit_log} 表的一行。
 */
public record AuditEntry(
        long id,
        long ts,
        String kind,
        String target,
        String detailJson,
        int tokens,
        long latencyMs
) {

    /** 单行可读摘要，供 REPL {@code /audit} 命令展示。 */
    public String summaryLine() {
        String time = Instant.ofEpochMilli(ts).toString().replace('T', ' ').substring(0, 19);
        return String.format("#%d  %s  [%s] %s  tokens=%d  latency=%dms",
                id, time, kind, target, tokens, latencyMs);
    }
}
