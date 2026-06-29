package com.agentcli.llm;

/**
 * 通用 OpenAI 兼容客户端。
 *
 * 通过 baseUrl + model 可配置接入任意 OpenAI 兼容网关（自建代理、第三方聚合、
 * Kimi / StepFun 等兼容端点），无需为每个厂商单独写实现，体现抽象层的可扩展性。
 */
public class GenericOpenAiClient extends OpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public GenericOpenAiClient(String apiKey, String model, String baseUrl) {
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
        return "openai";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }
}
