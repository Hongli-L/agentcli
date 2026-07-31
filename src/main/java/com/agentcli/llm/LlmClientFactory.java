package com.agentcli.llm;

import com.agentcli.common.AppConfig;

import java.util.List;
import java.util.Locale;

/**
 * 根据配置 / 环境变量创建对应的 {@link LlmClient}。
 *
 * 支持 provider：glm、deepseek、kimi、step、xfyun、agnes、openai（通用 OpenAI 兼容）。
 * 常见别名统一归一到规范 provider key：
 * <ul>
 *   <li>glm / zhipu / zhipuai -> glm</li>
 *   <li>deepseek -> deepseek</li>
 *   <li>kimi / moonshot / moonshotai / moonshot-ai -> kimi</li>
 *   <li>step / stepfun / step-fun -> step</li>
 *   <li>xfyun / xunfei / iflytek / spark / xinghuo / 讯飞 / 星火 / maas -> xfyun</li>
 *   <li>agnes / agnes-ai / sapiens / apihub -> agnes</li>
 *   <li>openai / oai / generic -> openai（通用网关）</li>
 * </ul>
 */
public final class LlmClientFactory {

    private static final String[] FALLBACK_ORDER =
            {"glm", "deepseek", "kimi", "step", "xfyun", "agnes", "openai"};

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
            case "kimi" -> new KimiCompatibleClient(apiKey, model, baseUrl);
            case "step" -> new StepCompatibleClient(apiKey, model, baseUrl);
            case "xfyun" -> new XfyunCompatibleClient(apiKey, model, baseUrl, config.getLoraId(normalized));
            case "agnes" -> new AgnesClient(apiKey, model, baseUrl);
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

    /** 已知 provider 名列表（供 /model 值补全使用，未来扩展只需改这里）。 */
    public static List<String> knownProviders() {
        return List.of("glm", "deepseek", "kimi", "step", "xfyun", "agnes", "openai");
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm", "zhipu", "zhipuai" -> "glm";
            case "deepseek" -> "deepseek";
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "step", "stepfun", "step-fun" -> "step";
            case "xfyun", "xf-yun", "xfyun-maas", "xfyun_maas", "maas",
                 "xunfei", "iflytek", "iflytek-maas", "spark", "xinghuo",
                 "讯飞", "星火" -> "xfyun";
            case "agnes", "agnes-ai", "agnes_ai", "agnesai",
                 "sapiens", "sapiens-ai", "apihub" -> "agnes";
            case "openai", "oai", "generic" -> "openai";
            default -> normalized;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
