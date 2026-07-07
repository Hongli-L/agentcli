package com.agentcli.common;

/**
 * 用户可见错误文案统一出口，避免各层散落硬编码格式。
 */
public final class UserFacingErrors {

    private UserFacingErrors() {
    }

    public static String fail(String message) {
        return "❌ " + safe(message, "发生未知错误");
    }

    public static String llmFailure(String message) {
        return fail("调用 LLM 失败: " + safe(message, "未知原因"));
    }

    public static String toolFailure(String message) {
        return "工具执行失败: " + safe(message, "未知错误");
    }

    public static String policyReject(String message) {
        return "🛡️ 策略拒绝: " + safe(message, "不允许的操作");
    }

    private static String safe(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.trim();
    }
}

