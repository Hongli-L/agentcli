package com.agentcli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端 HITL 审批处理器 —— 在终端展示审批请求，等待键盘输入后返回决策。
 *
 * <p>支持的交互选项：
 * <ul>
 *   <li>{@code y / Enter} — 批准本次操作</li>
 *   <li>{@code a} — 批准本次会话所有后续同类危险操作（工具维度）</li>
 *   <li>{@code n} — 拒绝本次操作</li>
 *   <li>{@code s} — 跳过本步骤</li>
 *   <li>{@code m} — 修改参数后执行（进入参数输入模式）</li>
 * </ul>
 *
 * <p>并发安全：{@link #requestApproval} 方法整体 synchronized，
 * 确保多工具并行时同一时刻只有一个审批提示活跃，避免 stdout 串扰与 stdin 争抢。
 */
public class TerminalHitlHandler implements HitlHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile boolean enabled;

    /** 本次会话中已批准"全部放行"的工具集合（并发安全）。 */
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();

    private final BufferedReader in;
    private final PrintStream out;

    public TerminalHitlHandler(boolean enabled) {
        this(enabled,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    /** 包可见构造器：允许测试注入自定义 stdin / stdout。 */
    TerminalHitlHandler(boolean enabled, BufferedReader in, PrintStream out) {
        this.enabled = enabled;
        this.in = in;
        this.out = out;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 展示审批请求并收集决策；整体 synchronized 以便并发调用时序列化。
     */
    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        if (isApprovedAllByTool(request.toolName())) {
            out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }

        out.println();
        out.println("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 \u26A0\uFE0F  HITL 审批请求 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
        out.println(request.toDisplayText());

        return promptUntilDecision(request);
    }

    /** 主交互循环：无法识别的输入会重新提示而非默认放行（fail-safe）。 */
    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {
            out.println();
            out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();
            } catch (IOException e) {
                out.println("  [HITL] 读取用户输入失败，保守处理为拒绝");
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭，保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();

            if (normalized.isEmpty() || normalized.equals("y")) {
                out.println("  已批准");
                return ApprovalResult.approve();
            }

            switch (normalized) {
                case "a" -> {
                    approvedAllByTool.add(request.toolName());
                    out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
                    return ApprovalResult.approveAll();
                }
                case "n" -> {
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();
                    } catch (IOException e) {
                        reason = "";
                    }
                    return ApprovalResult.reject(reason == null ? "" : reason.trim());
                }
                case "s" -> {
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) return modified;
                }
                default -> out.println(
                        "  \u2753 无法识别的选项：'" + input
                        + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");
            }
        }
        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    /** 修改参数子流程：验证用户输入为合法 JSON；非法则返回 null 让主循环重新提示。 */
    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            MAPPER.readTree(trimmed);
        } catch (Exception e) {
            out.println("  \u274C 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }
}
