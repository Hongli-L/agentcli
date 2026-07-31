package com.agentcli.llm;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.List;

/**
 * DeepSeek 客户端（OpenAI 兼容协议）。
 *
 * 默认走 HTTP/1.1，规避部分 HTTP/2 网关在长 SSE 响应下被重置导致的 stream reset。
 */
public class DeepSeekClient extends OpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final OkHttpClient HTTP_1_1_CLIENT = SHARED_HTTP_CLIENT.newBuilder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .build();

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public DeepSeekClient(String apiKey, String model, String baseUrl) {
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
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    protected OkHttpClient httpClient() {
        return HTTP_1_1_CLIENT;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 65_536;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return PromptCacheMode.DEEPSEEK;
    }
}
