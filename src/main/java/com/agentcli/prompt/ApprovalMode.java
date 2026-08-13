package com.agentcli.prompt;

import java.util.Locale;

/**
 * 系统提示词的<b>审批策略</b>维度（HITL 开关）：控制危险工具是否需要人工确认。
 *
 * <p>该维度在一次会话内基本稳定，排在缓存前缀中段，与 {@link PromptMode} 对称。
 *
 * <ul>
 *   <li>{@link #SUGGEST}（默认）：危险工具触发审批时按用户选择执行；</li>
 *   <li>{@link #AUTO}：偏自动执行，但仍受安全策略与路径围栏约束；</li>
 *   <li>{@link #NEVER}：偏保守，写文件 / 执行命令 / 恢复快照 / 外部 MCP 工具优先选可回滚、范围最小的操作。</li>
 * </ul>
 */
public enum ApprovalMode {

    SUGGEST("approvals/suggest.md"),
    AUTO("approvals/auto.md"),
    NEVER("approvals/never.md");

    private final String resourcePath;

    ApprovalMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** classpath 下模板相对路径（相对 {@code prompts/}）。 */
    public String resourcePath() {
        return resourcePath;
    }

    /** 容错解析：未知 / 空值一律回退 {@link #SUGGEST}，不抛异常。 */
    public static ApprovalMode from(String name) {
        if (name == null || name.isBlank()) {
            return SUGGEST;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "never" -> NEVER;
            default -> SUGGEST;
        };
    }
}
