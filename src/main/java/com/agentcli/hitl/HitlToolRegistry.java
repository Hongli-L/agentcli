package com.agentcli.hitl;

import com.agentcli.audit.AuditLog;
import com.agentcli.tool.ToolRegistry;

/**
 * HITL 工具注册表 —— 在危险工具调用前插入人工审批。
 *
 * <p>继承自 {@link ToolRegistry}，覆写 {@link #doExecuteTool} 方法，
 * 在执行写操作（write_file / execute_command / create_project）之前
 * 通过 {@link HitlHandler} 向用户请求审批。
 *
 * <p>如果 HITL 未启用，行为与父类完全相同，无额外开销。
 * 如果工具不需要审批，直接委托父类执行。
 *
 * <h3>审批决策流程</h3>
 * <ol>
 *   <li>HITL 未启用 → 直接执行</li>
 *   <li>工具不在危险名单中 → 直接执行</li>
 *   <li>工具已在本次会话中"全部放行" → 直接执行</li>
 *   <li>否则 → 弹出审批交互，根据用户选择执行/拒绝/跳过/修改参数</li>
 * </ol>
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;
    private final AuditLog auditLog;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        this(hitlHandler, null);
    }

    public HitlToolRegistry(String projectPath, HitlHandler hitlHandler) {
        this(projectPath, hitlHandler, null);
    }

    public HitlToolRegistry(HitlHandler hitlHandler, AuditLog auditLog) {
        super();
        this.hitlHandler = hitlHandler;
        this.auditLog = auditLog;
    }

    public HitlToolRegistry(String projectPath, HitlHandler hitlHandler, AuditLog auditLog) {
        super(projectPath);
        this.hitlHandler = hitlHandler;
        this.auditLog = auditLog;
    }

    @Override
    protected String doExecuteTool(String name, String argumentsJson) {
        // 如果 HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        // 如果已在本次会话中全部放行，直接执行
        if (hitlHandler.isApprovedAllByTool(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        // 需要人工审批
        return executeWithApproval(name, argumentsJson);
    }

    private String executeWithApproval(String name, String argumentsJson) {
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (auditLog != null) {
            auditLog.recordApproval(name, result.decision().name(), result.reason(), summarize(argumentsJson));
        }

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            return "[HITL] 操作已被拒绝：" + reason;
        }

        if (result.isSkipped()) {
            return "[HITL] 操作已被跳过";
        }

        // 批准执行（含修改参数）
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.doExecuteTool(name, effectiveArgs);
    }

    /** 把工具参数截断成审计可读的摘要（避免把完整参数落盘）。 */
    private static String summarize(String argumentsJson) {
        if (argumentsJson == null) {
            return "";
        }
        int max = 120;
        return argumentsJson.length() <= max ? argumentsJson : argumentsJson.substring(0, max) + "…";
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
