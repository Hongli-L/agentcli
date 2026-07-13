package com.agentcli.hitl;

/**
 * 审批结果 —— 用户对一次工具调用审批的决策。
 *
 * <p>决策类型：
 * <ul>
 *   <li>{@link Decision#APPROVED} — 批准本次执行，使用原始参数</li>
 *   <li>{@link Decision#APPROVED_ALL} — 批准本次会话所有后续同工具操作</li>
 *   <li>{@link Decision#REJECTED} — 拒绝执行，Agent 收到拒绝通知后可重新规划</li>
 *   <li>{@link Decision#MODIFIED} — 修改参数后执行</li>
 *   <li>{@link Decision#SKIPPED} — 跳过本步骤，继续后续操作</li>
 * </ul>
 */
public record ApprovalResult(
        Decision decision,
        String modifiedArguments,
        String reason
) {

    public enum Decision {
        APPROVED,
        APPROVED_ALL,
        REJECTED,
        MODIFIED,
        SKIPPED
    }

    // ---- 工厂方法 ----

    public static ApprovalResult approve() {
        return new ApprovalResult(Decision.APPROVED, null, null);
    }

    public static ApprovalResult approveAll() {
        return new ApprovalResult(Decision.APPROVED_ALL, null, null);
    }

    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(Decision.REJECTED, null, reason);
    }

    public static ApprovalResult modify(String modifiedArguments) {
        return new ApprovalResult(Decision.MODIFIED, modifiedArguments, null);
    }

    public static ApprovalResult skip() {
        return new ApprovalResult(Decision.SKIPPED, null, null);
    }

    // ---- 判断方法 ----

    /** 是否批准执行（包含 APPROVED / APPROVED_ALL / MODIFIED）。 */
    public boolean isApproved() {
        return decision == Decision.APPROVED
                || decision == Decision.APPROVED_ALL
                || decision == Decision.MODIFIED;
    }

    /** 是否"全部放行"（本次会话后续同类工具自动通过）。 */
    public boolean isApprovedAll() {
        return decision == Decision.APPROVED_ALL;
    }

    /** 是否拒绝。 */
    public boolean isRejected() {
        return decision == Decision.REJECTED;
    }

    /** 是否跳过。 */
    public boolean isSkipped() {
        return decision == Decision.SKIPPED;
    }

    /**
     * 获取实际执行时应使用的参数。
     * 如果是 MODIFIED 且提供了有效参数，返回修改后的；否则返回原始参数。
     */
    public String effectiveArguments(String originalArguments) {
        if (decision == Decision.MODIFIED
                && modifiedArguments != null
                && !modifiedArguments.isBlank()) {
            return modifiedArguments;
        }
        return originalArguments;
    }
}
