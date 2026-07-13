package com.agentcli.hitl;

/**
 * HITL 审批交互接口 —— 定义人工审批的交互契约。
 *
 * <p>实现类负责与用户交互，收集用户对危险操作的审批决策。
 * 当前提供基于终端的实现（{@link TerminalHitlHandler}）。
 *
 * <h3>设计约定</h3>
 * <ul>
 *   <li>审批是同步阻塞操作，实现类需等待用户输入后才返回</li>
 *   <li>实现类不负责判断"是否需要审批"，该判断由 {@link ApprovalPolicy} 负责</li>
 *   <li>实现类只负责"展示请求 + 收集决策"</li>
 * </ul>
 */
public interface HitlHandler {

    /**
     * 向用户展示审批请求并收集决策。
     *
     * @param request 待审批的工具调用信息
     * @return 用户的审批决策
     */
    ApprovalResult requestApproval(ApprovalRequest request);

    /**
     * HITL 是否处于启用状态。
     * 未启用时调用方可跳过审批流程。
     */
    boolean isEnabled();

    /**
     * 启用/禁用 HITL 审批。
     */
    void setEnabled(boolean enabled);

    /**
     * 某工具在本次会话中是否已被"全部放行"。
     */
    default boolean isApprovedAllByTool(String toolName) {
        return false;
    }

    /**
     * 清除本次会话中所有"全部放行"记录。
     */
    default void clearApprovedAll() {
    }
}
