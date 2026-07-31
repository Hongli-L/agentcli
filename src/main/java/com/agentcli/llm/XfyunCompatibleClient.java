package com.agentcli.llm;

import com.agentcli.common.EnvLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.Request;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 讯飞（Xfyun）客户端（OpenAI 兼容协议，Bearer 鉴权）。
 *
 * <p>复用 {@link OpenAiCompatibleClient} 的 HTTP / SSE / 工具闭环逻辑，仅覆写
 * url / model / apiKey 三个基础钩子与少量讯飞定制钩子。讯飞侧存在两种 OpenAI 兼容形态，
 * 由 baseUrl 的 host 自动判定（见 {@link Flavor}）：
 * <ul>
 *   <li>{@link Flavor#SPARK_OPEN}：星火大模型开放平台 {@code https://spark-api-open.xf-yun.com/v1}，
 *       默认模型 {@code 4.0Ultra}，支持 {@code tool_calls}，但需显式打开
 *       {@code tool_calls_switch} 开关；</li>
 *   <li>{@link Flavor#MAAS}：MaaS 网关（host 含 {@code maas-api}），常用于自训/微调模型，
 *       需显式请求 {@code stream_options.include_usage} 才会回传 usage；
 *       微调模型通过 {@code lora_id} 请求头指定。</li>
 * </ul>
 *
 * <p>鉴权统一走 Bearer（基类已注入 {@code Authorization: Bearer <apiKey>}），
 * 不涉及任何 HMAC 签名逻辑。
 */
public class XfyunCompatibleClient extends OpenAiCompatibleClient {

    /** 星火开放平台默认端点。 */
    static final String DEFAULT_BASE_URL = "https://spark-api-open.xf-yun.com/v1";

    /** 星火开放平台默认模型。 */
    static final String DEFAULT_MODEL = "4.0Ultra";

    /** 强制覆盖工具能力判定的环境变量 / 系统属性名。 */
    static final String SUPPORTS_TOOLS_ENV = "XFYUN_SUPPORTS_TOOLS";

    /** 已确认支持 function calling 的星火模型（全小写比较）。 */
    private static final Set<String> TOOL_CAPABLE_MODELS = Set.of("4.0ultra", "generalv3.5", "max-32k");

    /** 模型 -> 上下文窗口（token）映射（全小写比较）。 */
    private static final Map<String, Integer> CONTEXT_WINDOWS = Map.of(
            "4.0ultra", 32_768,
            "max-32k", 32_768,
            "pro-128k", 131_072,
            "lite", 8_192,
            "generalv3", 8_192);

    /** 未在映射表中的模型（含 MaaS 自训模型）统一按 32K 估算。 */
    private static final int DEFAULT_CONTEXT_WINDOW = 32_768;

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final String loraId;
    private final Flavor flavor;

    /** 讯飞 OpenAI 兼容端点的两种形态。 */
    enum Flavor {
        /** 星火大模型开放平台。 */
        SPARK_OPEN,
        /** MaaS 网关（自训 / 微调模型）。 */
        MAAS
    }

    public XfyunCompatibleClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null, null);
    }

    public XfyunCompatibleClient(String apiKey, String model) {
        this(apiKey, model, null, null);
    }

    public XfyunCompatibleClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, null);
    }

    public XfyunCompatibleClient(String apiKey, String model, String baseUrl, String loraId) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = resolveChatCompletionsUrl(baseUrl, DEFAULT_BASE_URL);
        this.loraId = loraId == null || loraId.isBlank() ? null : loraId.trim();
        this.flavor = resolveFlavor(this.apiUrl);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "xfyun";
    }

    /** 当前生效的端点形态（供测试与诊断使用）。 */
    Flavor flavor() {
        return flavor;
    }

    /** 微调模型 lora_id，未配置时为 {@code null}。 */
    String loraId() {
        return loraId;
    }

    @Override
    public int maxContextWindow() {
        Integer window = CONTEXT_WINDOWS.get(model.toLowerCase(Locale.ROOT));
        return window != null ? window : DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * 是否支持 function calling。
     *
     * <p>优先读取 {@value #SUPPORTS_TOOLS_ENV}（非空且不等于 {@code false} 视为开启），
     * 便于用户为白名单外的新模型 / MaaS 自训模型手工开启；未配置时按白名单判定。
     */
    @Override
    public boolean supportsTools() {
        String override = EnvLoader.get(SUPPORTS_TOOLS_ENV);
        if (override != null && !override.isBlank()) {
            return !"false".equalsIgnoreCase(override.trim());
        }
        return TOOL_CAPABLE_MODELS.contains(model.toLowerCase(Locale.ROOT));
    }

    /**
     * 星火开放平台需显式打开 {@code tool_calls_switch} 才会返回 tool_calls；
     * MaaS 网关需显式请求 {@code stream_options.include_usage} 才会在流末回传 usage。
     */
    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (flavor == Flavor.SPARK_OPEN && supportsTools()) {
            requestBody.put("tool_calls_switch", true);
        }
        if (flavor == Flavor.MAAS) {
            streamOptions(requestBody).put("include_usage", true);
        }
    }

    /** MaaS 微调模型通过 {@code lora_id} 请求头指定。 */
    @Override
    protected void customizeRequest(Request.Builder request) {
        if (loraId != null && !loraId.isBlank()) {
            request.header("lora_id", loraId);
        }
    }

    /**
     * 讯飞在 HTTP 200 的 SSE 分片里用业务码 {@code code} 表达错误（0 为成功），
     * 与 OpenAI 的 {@code error} 节点不同，需单独识别，避免被当成「空内容」。
     */
    @Override
    protected void inspectStreamChunk(JsonNode root) throws IOException {
        if (root.has("code")) {
            int code = root.get("code").asInt();
            if (code != 0) {
                throw new IOException("API 请求失败: " + code + " - " + root.path("message").asText());
            }
        }
    }

    private static ObjectNode streamOptions(ObjectNode requestBody) {
        JsonNode existing = requestBody.get("stream_options");
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        return requestBody.putObject("stream_options");
    }

    /** host 含 {@code maas-api} 判为 MaaS 网关，否则按星火开放平台处理。 */
    static Flavor resolveFlavor(String url) {
        if (url == null || url.isBlank()) {
            return Flavor.SPARK_OPEN;
        }
        HttpUrl parsed = HttpUrl.parse(url.trim());
        String host = parsed != null ? parsed.host() : url.trim();
        return host.toLowerCase(Locale.ROOT).contains("maas-api") ? Flavor.MAAS : Flavor.SPARK_OPEN;
    }
}
