package com.agentcli.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenUsageFormatterTest {

    private ContextProfile profile(String provider, int input, int output, int cached, int window) {
        return new ContextProfile(provider, "test-model", "/tmp/proj", 5, 0, 0, 3,
                input, output, cached, window);
    }

    @Test
    void deepseekUsesDiscountedPrices() {
        double[] price = TokenUsageFormatter.pricePerMillion("deepseek");
        assertEquals(2.0, price[0], 1e-9);
        assertEquals(0.5, price[1], 1e-9);
        assertEquals(8.0, price[2], 1e-9);
    }

    @Test
    void glmKimiStepShareStandardPrices() {
        for (String provider : new String[]{"glm", "kimi", "step"}) {
            double[] price = TokenUsageFormatter.pricePerMillion(provider);
            assertEquals(5.0, price[0], 1e-9, provider);
            assertEquals(1.0, price[1], 1e-9, provider);
            assertEquals(15.0, price[2], 1e-9, provider);
        }
    }

    @Test
    void unknownProviderFallsBackToDefaultPrices() {
        double[] price = TokenUsageFormatter.pricePerMillion("no-such-vendor");
        assertEquals(5.0, price[0], 1e-9);
        assertEquals(1.0, price[1], 1e-9);
        assertEquals(15.0, price[2], 1e-9);

        double[] nullPrice = TokenUsageFormatter.pricePerMillion(null);
        assertEquals(5.0, nullPrice[0], 1e-9);
    }

    @Test
    void providerLookupIsCaseInsensitive() {
        assertEquals(2.0, TokenUsageFormatter.pricePerMillion("DeepSeek")[0], 1e-9);
    }

    @Test
    void cachedTokensAreBilledAtDiscountRate() {
        // deepseek: 1M input 中 400k 命中缓存 -> 600k*2.0 + 400k*0.5 + 1M*8.0 = 1.2 + 0.2 + 8.0
        double cost = TokenUsageFormatter.estimatedCostCny("deepseek", 1_000_000, 1_000_000, 400_000);
        assertEquals(9.4, cost, 1e-9);
    }

    @Test
    void costWithoutCacheMatchesPlainRates() {
        double cost = TokenUsageFormatter.estimatedCostCny("glm", 1_000_000, 1_000_000, 0);
        assertEquals(20.0, cost, 1e-9);
    }

    @Test
    void cachedGreaterThanInputIsClampedNotNegative() {
        double cost = TokenUsageFormatter.estimatedCostCny("deepseek", 1_000, 0, 9_999);
        // cached 被夹到 input 上限，全部按缓存价计费：1000 * 0.5 / 1e6
        assertEquals(0.0005, cost, 1e-12);
        assertTrue(cost > 0);
    }

    @Test
    void negativeInputsAreTreatedAsZero() {
        assertEquals(0.0, TokenUsageFormatter.estimatedCostCny("glm", -10, -10, -10), 1e-12);
    }

    @Test
    void envLoaderOverridesPrices() {
        // EnvLoader 支持 JVM 系统属性，作为环境变量的等价覆盖来源
        System.setProperty("agentcli.price.deepseek.input", "1.5");
        System.setProperty("agentcli.price.deepseek.cached", "0.1");
        System.setProperty("agentcli.price.deepseek.output", "3.0");
        try {
            double[] price = TokenUsageFormatter.pricePerMillion("deepseek");
            assertEquals(1.5, price[0], 1e-9);
            assertEquals(0.1, price[1], 1e-9);
            assertEquals(3.0, price[2], 1e-9);
        } finally {
            System.clearProperty("agentcli.price.deepseek.input");
            System.clearProperty("agentcli.price.deepseek.cached");
            System.clearProperty("agentcli.price.deepseek.output");
        }
    }

    @Test
    void malformedOverrideSilentlyFallsBack() {
        System.setProperty("agentcli.price.glm.input", "not-a-number");
        try {
            assertEquals(5.0, TokenUsageFormatter.pricePerMillion("glm")[0], 1e-9);
        } finally {
            System.clearProperty("agentcli.price.glm.input");
        }
    }

    @Test
    void formatContainsThousandSeparatorsAndEstimateWording() {
        String text = TokenUsageFormatter.format(profile("deepseek", 1_234_567, 2_000, 0, 128_000));

        assertTrue(text.contains("1,234,567"), text);
        assertTrue(text.contains("估算"), text);
        assertTrue(text.contains("Token 用量"), text);
    }

    @Test
    void formatHandlesNullProfileGracefully() {
        String text = TokenUsageFormatter.format(null);
        assertTrue(text.contains("Token 用量"), text);
    }

    @Test
    void formatOutputHasNoAnsiEscapesWhenColorDisabled() {
        // AnsiStyle 在启用着色时才输出转义码；关闭时必须是纯文本
        String text = TokenUsageFormatter.format(profile("glm", 1_000, 1_000, 0, 128_000));
        if (!com.agentcli.common.AnsiStyle.isEnabled()) {
            assertTrue(text.indexOf('\u001B') < 0, "着色关闭时不应出现 ANSI 转义码: " + text);
        } else {
            assertTrue(text.contains("Token 用量"), text);
        }
    }

    @Test
    void highWindowUsageStillRendersUsageLine() {
        String text = TokenUsageFormatter.format(profile("glm", 90_000, 30_000, 0, 128_000));
        assertTrue(text.contains("窗口占用"), text);
        assertTrue(text.contains("120,000"), text);
    }

    @Test
    void formatPerTurnShowsTokensCostAndTime() {
        String text = TokenUsageFormatter.formatPerTurn("deepseek", "deepseek-chat",
                10_000, 2_000, 4_000, 1234);
        assertTrue(text.contains("10,000"), text);
        assertTrue(text.contains("2,000"), text);
        assertTrue(text.contains("4,000"), text);
        assertTrue(text.contains("估算"), text);
        assertTrue(text.contains("¥"), text);
        assertTrue(text.contains("⏱"), text);
        assertTrue(text.contains("1.23"), text); // 1234ms -> 1.23s
        assertTrue(text.contains("deepseek"), text);
    }

    @Test
    void formatPerTurnHasNoAnsiWhenColorDisabled() {
        String text = TokenUsageFormatter.formatPerTurn("glm", "glm-4", 100, 100, 0, 500);
        if (!com.agentcli.common.AnsiStyle.isEnabled()) {
            assertTrue(text.indexOf('\u001B') < 0, "着色关闭时不应出现 ANSI 转义码: " + text);
        } else {
            assertTrue(text.contains("本回合"), text);
        }
    }
}
