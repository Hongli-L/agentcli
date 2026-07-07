package com.agentcli.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserFacingErrorsTest {

    @Test
    void llmFailureUsesUnifiedPrefix() {
        assertEquals("❌ 调用 LLM 失败: timeout", UserFacingErrors.llmFailure("timeout"));
    }

    @Test
    void policyRejectProvidesFallback() {
        assertEquals("🛡️ 策略拒绝: 不允许的操作", UserFacingErrors.policyReject(" "));
    }
}

