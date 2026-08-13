package com.agentcli.prompt;

import java.util.Locale;

/**
 * 系统提示词的<b>行为模式</b>维度：对应 Agent 在不同场景下的角色 stance。
 *
 * <p>该维度在一次会话内基本稳定，因此排在缓存前缀中段——切换模式会改变前缀、
 * 但同一模式下的多轮对话共享同一个稳定前缀，利于上游 Prompt Cache 命中。
 *
 * <p>与 {@link ApprovalMode} 共同构成 v0.21.0 的「分层 Prompt 组装」两维度。
 */
public enum PromptMode {

    DEFAULT("modes/default.md"),
    PLAN("modes/plan.md"),
    REVIEW("modes/review.md");

    private final String resourcePath;

    PromptMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** classpath 下模板相对路径（相对 {@code prompts/}）。 */
    public String resourcePath() {
        return resourcePath;
    }

    /** 容错解析：未知 / 空值一律回退 {@link #DEFAULT}，不抛异常。 */
    public static PromptMode from(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "plan" -> PLAN;
            case "review" -> REVIEW;
            default -> DEFAULT;
        };
    }
}
