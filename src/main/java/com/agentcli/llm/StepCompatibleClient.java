package com.agentcli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * StepFun（阶跃星辰）客户端（OpenAI 兼容协议）。
 *
 * <p>直接复用 {@link OpenAiCompatibleClient} 的 HTTP / SSE / 工具闭环逻辑，
 * 仅覆写 url / model / apiKey 三个基础钩子，以及为 StepFun 定制的请求体钩子：
 * <ul>
 *   <li>默认 base URL {@code https://api.stepfun.com/v1}，默认模型 {@code step-3.5-flash}；</li>
 *   <li>provider key 为 {@code step}（别名 stepfun / step-fun）；</li>
 *   <li>上下文窗口 256000；</li>
 *   <li>请求体固定注入 {@code reasoning_format="deepseek-style"}；若模型名含 {@code 2603} 再注入
 *       {@code reasoning_effort="high"}。</li>
 * </ul>
 */
public class StepCompatibleClient extends OpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.stepfun.com/v1";
    private static final String DEFAULT_MODEL = "step-3.5-flash";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public StepCompatibleClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null);
    }

    public StepCompatibleClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public StepCompatibleClient(String apiKey, String model, String baseUrl) {
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
    protected void customizeRequestBody(ObjectNode requestBody) {
        requestBody.put("reasoning_format", "deepseek-style");
        if (model != null && model.contains("2603")) {
            requestBody.put("reasoning_effort", "high");
        }
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "step";
    }

    @Override
    public int maxContextWindow() {
        return 256_000;
    }
}
