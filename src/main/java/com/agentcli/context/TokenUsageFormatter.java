package com.agentcli.context;

import com.agentcli.common.AnsiStyle;
import com.agentcli.common.EnvLoader;

import java.util.Locale;

/**
 * {@code /usage} 报表渲染：把 {@link ContextProfile} 里的 token 数字翻译成人话和钱。
 *
 * <p>价格说明：内置单价是"元 / 百万 token"的粗略挂牌价，随时可能与实际账单有出入，
 * 因此所有金额输出<b>恒定带"估算"二字</b>，避免用户当成对账依据。
 * 需要精确值时可用 {@code agentcli.price.<provider>.input|cached|output} 覆盖。
 */
public final class TokenUsageFormatter {

    /** 兜底单价（元/百万 token），顺序为 [input, cachedInput, output]。 */
    private static final double[] FALLBACK_PRICE = {5.0, 1.0, 15.0};

    /** 窗口占用率告警阈值，超过则用 error 色提示。 */
    private static final double WARN_RATIO = 0.8;

    private TokenUsageFormatter() {
    }

    /** 渲染 {@code /usage} 报表文本（带 ANSI 着色，NO_COLOR 下自动降级为纯文本）。 */
    public static String format(ContextProfile profile) {
        if (profile == null) {
            return AnsiStyle.section("Token 用量") + "\n" + AnsiStyle.subtle("  暂无可用的用量数据。");
        }

        int input = profile.usedInputTokens();
        int output = profile.usedOutputTokens();
        int cached = profile.cachedInputTokens();
        int total = profile.totalUsedTokens();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.section("Token 用量")).append('\n');
        sb.append(line("模型", profile.provider() + " / " + profile.model())).append('\n');
        sb.append(line("调用次数", formatNumber(profile.llmCallCount()) + " 次")).append('\n');
        sb.append(line("输入 token", formatNumber(input))).append('\n');
        sb.append(line("其中缓存命中", formatNumber(cached))).append('\n');
        sb.append(line("输出 token", formatNumber(output))).append('\n');
        sb.append(line("合计 token", formatNumber(total))).append('\n');

        String window = formatNumber(total) + " / " + formatNumber(profile.contextWindow())
                + String.format(Locale.ROOT, " (%.1f%%)", profile.windowUsageRatio() * 100);
        sb.append(AnsiStyle.subtle("  窗口占用      : "))
                .append(profile.windowUsageRatio() >= WARN_RATIO ? AnsiStyle.error(window) : window)
                .append('\n');

        double cost = estimatedCostCny(profile.provider(), input, output, cached);
        sb.append(AnsiStyle.subtle("  费用估算      : "))
                .append(AnsiStyle.emphasis(String.format(Locale.ROOT, "≈ %.4f 元（估算，仅供参考）", cost)));
        return sb.toString();
    }

    /**
     * 渲染单轮回合的小结（紧凑单行，供每轮结束即时展示）。
     *
     * <p>与 {@link #format} 的多行 {@code /usage} 报表不同，这里只给一行：
     * 本回合输入/输出/缓存 token、估算费用、耗时，便于用户每次提问后立刻看到代价。
     */
    public static String formatPerTurn(String provider, String model,
                                        int inputTokens, int outputTokens, int cachedInputTokens,
                                        long elapsedMillis) {
        double cost = estimatedCostCny(provider, inputTokens, outputTokens, cachedInputTokens);
        double seconds = elapsedMillis / 1000.0;
        return AnsiStyle.subtle("📊 本回合 ")
                + "输入 " + formatNumber(Math.max(0, inputTokens))
                + " / 输出 " + formatNumber(Math.max(0, outputTokens))
                + " / 缓存 " + formatNumber(Math.max(0, cachedInputTokens))
                + AnsiStyle.subtle("　估算 ")
                + AnsiStyle.emphasis(String.format(Locale.ROOT, "¥%.4f", cost))
                + AnsiStyle.subtle("　⏱ ")
                + String.format(Locale.ROOT, "%.2f", seconds) + "s"
                + AnsiStyle.subtle("　(" + (provider == null ? "?" : provider) + "/" + (model == null ? "?" : model) + ")");
    }

    /**
     * 估算本次会话花费（人民币元）。
     *
     * <p>缓存命中的输入 token 按低价档单独计费，因此普通输入部分要把 cached 扣掉；
     * 若上游把 cached 报得比 input 还大（脏数据），这里按 0 处理而不是算出负数。
     */
    static double estimatedCostCny(String provider, int inputTokens, int outputTokens, int cachedTokens) {
        double[] price = pricePerMillion(provider);
        int safeInput = Math.max(0, inputTokens);
        int safeOutput = Math.max(0, outputTokens);
        int safeCached = Math.min(Math.max(0, cachedTokens), safeInput);
        int billableInput = safeInput - safeCached;

        return (billableInput * price[0] + safeCached * price[1] + safeOutput * price[2]) / 1_000_000.0;
    }

    /**
     * 取某 provider 的单价表 [input, cachedInput, output]，单位：元/百万 token。
     *
     * <p>支持通过 {@code agentcli.price.<provider>.input|cached|output} 覆盖，
     * 解析失败静默回退默认值——配置写错不该让 {@code /usage} 崩掉。
     */
    static double[] pricePerMillion(String provider) {
        String key = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        double[] base = switch (key) {
            case "deepseek" -> new double[]{2.0, 0.5, 8.0};
            case "glm" -> new double[]{5.0, 1.0, 15.0};
            case "kimi" -> new double[]{5.0, 1.0, 15.0};
            case "step" -> new double[]{5.0, 1.0, 15.0};
            default -> FALLBACK_PRICE.clone();
        };
        return new double[]{
                override(key, "input", base[0]),
                override(key, "cached", base[1]),
                override(key, "output", base[2])
        };
    }

    private static double override(String provider, String field, double defaultValue) {
        if (provider.isEmpty()) {
            return defaultValue;
        }
        String raw = EnvLoader.get("agentcli.price." + provider + "." + field);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String line(String label, String value) {
        return AnsiStyle.subtle("  " + pad(label) + ": ") + value;
    }

    /** 中文标签按显示宽度对齐，避免中英文混排时列错位。 */
    private static String pad(String label) {
        int width = AnsiStyle.displayWidth(label);
        int target = 14;
        return label + " ".repeat(Math.max(1, target - width));
    }

    private static String formatNumber(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
