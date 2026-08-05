package com.agentcli.audit;

import com.agentcli.store.SqliteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用与工具审批的结构化审计日志，落 v0.9.0 预留的 SQLite {@code audit_log} 表。
 *
 * <p>落盘是旁路观测：任何写入或查询异常都只打印告警、绝不中断 Agent 主流程。
 * 每次操作通过 {@link SqliteStore#connect()} 打开短生命周期连接，由调用方关闭。
 */
public class AuditLog {

    private final SqliteStore store;
    private final ObjectMapper json = new ObjectMapper();

    public AuditLog(SqliteStore store) {
        this.store = store;
    }

    /** 记录一次 LLM 调用（模型 / token / 耗时 / reasoning 摘要）。 */
    public void recordLlm(String model, int inputTokens, int outputTokens,
                          int cachedInputTokens, long latencyMs, String reasoningSummary,
                          boolean hasToolCalls) {
        ObjectNode detail = json.createObjectNode();
        detail.put("model", model);
        detail.put("input_tokens", inputTokens);
        detail.put("output_tokens", outputTokens);
        detail.put("cached_input_tokens", cachedInputTokens);
        detail.put("has_tool_calls", hasToolCalls);
        if (reasoningSummary != null) {
            detail.put("reasoning_summary", reasoningSummary);
        }
        insert("llm", model, detail.toString(), inputTokens + outputTokens, latencyMs);
    }

    /** 记录一次工具审批决策（批准 / 拒绝 / 跳过 / 修改 / 全部放行）。 */
    public void recordApproval(String toolName, String decision, String reason, String argsSummary) {
        ObjectNode detail = json.createObjectNode();
        detail.put("tool", toolName);
        detail.put("decision", decision);
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason);
        }
        if (argsSummary != null && !argsSummary.isBlank()) {
            detail.put("args_summary", argsSummary);
        }
        insert("approval", toolName, detail.toString(), null, null);
    }

    /**
     * 写入一条审计记录。
     *
     * <p>包级可见而非私有：v0.20.0 的 {@link OrchestrationTrace} 复用同一张 {@code audit_log} 表
     * 落编排轨迹（{@code kind="orchestration"}），共用这里的短连接 + 异常吞掉语义，
     * 无需为它另开一套公共 API 或复制一份 SQL。
     */
    void insert(String kind, String target, String detailJson, Integer tokens, Long latencyMs) {
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO audit_log (ts, kind, target, detail_json, tokens, latency_ms) "
                             + "VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, kind);
            ps.setString(3, target);
            ps.setString(4, detailJson);
            if (tokens != null) {
                ps.setInt(5, tokens);
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            if (latencyMs != null) {
                ps.setLong(6, latencyMs);
            } else {
                ps.setNull(6, Types.INTEGER);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[audit] 记录失败 (" + kind + "): " + e.getMessage());
        }
    }

    /** 最近 {@code limit} 条审计记录（时间倒序）。 */
    public List<AuditEntry> query(int limit) {
        return query(limit, null);
    }

    /** 按类型过滤的最近 {@code limit} 条审计记录（时间倒序）。 */
    public List<AuditEntry> query(int limit, String kind) {
        List<AuditEntry> out = new ArrayList<>();
        String sql = (kind == null || kind.isBlank())
                ? "SELECT id, ts, kind, target, detail_json, tokens, latency_ms FROM audit_log ORDER BY ts DESC LIMIT ?"
                : "SELECT id, ts, kind, target, detail_json, tokens, latency_ms FROM audit_log WHERE kind = ? ORDER BY ts DESC LIMIT ?";
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (kind == null || kind.isBlank()) {
                ps.setInt(1, limit);
            } else {
                ps.setString(1, kind);
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AuditEntry(
                            rs.getLong("id"),
                            rs.getLong("ts"),
                            rs.getString("kind"),
                            rs.getString("target"),
                            rs.getString("detail_json"),
                            rs.getInt("tokens"),
                            rs.getLong("latency_ms")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[audit] 查询失败: " + e.getMessage());
        }
        return out;
    }
}
