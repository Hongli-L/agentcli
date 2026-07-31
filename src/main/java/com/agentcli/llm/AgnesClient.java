package com.agentcli.llm;

/**
 * Agnes（apihub.agnes-ai.com）客户端（OpenAI 兼容协议）。
 *
 * <p>Agnes 官方端点在请求体、Bearer 鉴权、tools 协议与 SSE 分片格式上与 OpenAI 完全兼容，
 * 因此直接复用 {@link OpenAiCompatibleClient} 的模板方法，只覆写 url / model / apiKey
 * 三个基础钩子与四个元数据方法，不引入任何定制逻辑（无请求体改写、无请求头注入）。
 * 这正是模板方法模式吸收厂商差异的收益：新增厂商 = 一个薄子类。
 *
 * <ul>
 *   <li>默认 base URL {@code https://apihub.agnes-ai.com/v1}，默认模型 {@code agnes-2.5-flash}；</li>
 *   <li>provider key 为 {@code agnes}（别名 agnes-ai / sapiens / apihub 等）；</li>
 *   <li>上下文窗口 1,000,000；</li>
 *   <li>支持 function calling；暂未提供提示词缓存。</li>
 * </ul>
 */
public class AgnesClient extends OpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1";
    private static final String DEFAULT_MODEL = "agnes-2.5-flash";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public AgnesClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null);
    }

    public AgnesClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public AgnesClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = resolveChatCompletionsUrl(baseUrl, DEFAULT_BASE_URL);
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
        return "agnes";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsPromptCaching() {
        return false;
    }

    @Override
    public String promptCacheMode() {
        return PromptCacheMode.NONE;
    }
}
