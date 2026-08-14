package com.agentcli.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallQuotaTest {

    @Test
    void consumesUpToMax() {
        ToolCallQuota quota = new ToolCallQuota(3);
        assertTrue(quota.tryConsume());
        assertTrue(quota.tryConsume());
        assertTrue(quota.tryConsume());
        assertFalse(quota.tryConsume(), "超过上限应拒绝");
        assertEquals(0, quota.remaining());
        assertEquals(3, quota.used());
        assertEquals(3, quota.max());
    }

    @Test
    void rejectsNonPositiveMax() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCallQuota(0));
        assertThrows(IllegalArgumentException.class, () -> new ToolCallQuota(-1));
    }

    @Test
    void mixtureOfConsumeAndQuery() {
        ToolCallQuota quota = new ToolCallQuota(2);
        assertEquals(2, quota.remaining());
        quota.tryConsume();
        assertEquals(1, quota.remaining());
        assertTrue(quota.hasRemaining());
        quota.tryConsume();
        assertFalse(quota.hasRemaining());
    }
}
