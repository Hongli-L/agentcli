package com.agentcli.llm;

/**
 * 智谱 GLM 客户端（OpenAI 兼容协议）。
 */
public class GlmClient extends OpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String DEFAULT_MODEL = "glm-4-flash";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public GlmClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null);
    }

    public GlmClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public GlmClient(String apiKey, String model, String baseUrl) {
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
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return PromptCacheMode.GLM;
    }
}
