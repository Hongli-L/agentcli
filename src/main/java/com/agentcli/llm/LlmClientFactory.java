package com.agentcli.llm;

import com.agentcli.common.AppConfig;

import java.util.Locale;

/**
 * 根据配置 / 环境变量创建对应的 {@link LlmClient}。
 *
 * 支持三类 provider：glm、deepseek、openai（通用 OpenAI 兼容）。
 * 常见别名（kimi/moonshot/step/stepfun/oai/generic）统一归一到 openai 通用客户端。
 */
public final class LlmClientFactory {

    private static final String[] FALLBACK_ORDER = {"glm", "deepseek", "openai"};

    private LlmClientFactory() {
    }

    public static LlmClient create(String provider, AppConfig config) {
        if (provider == null || config == null) {
            return null;
        }
        String normalized = normalizeProvider(provider);
        String apiKey = config.getApiKey(normalized);
        if (isBlank(apiKey)) {
            return null;
        }
        String model = config.getModel(normalized);
        String baseUrl = config.getBaseUrl(normalized);

        return switch (normalized) {
            case "glm" -> new GlmClient(apiKey, model, baseUrl);
            case "deepseek" -> new DeepSeekClient(apiKey, model, baseUrl);
            case "openai" -> new GenericOpenAiClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(AppConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }
        for (String provider : FALLBACK_ORDER) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }
        return null;
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm", "zhipu", "zhipuai" -> "glm";
            case "deepseek" -> "deepseek";
            case "openai", "oai", "generic", "kimi", "moonshot", "step", "stepfun" -> "openai";
            default -> normalized;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
