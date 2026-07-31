package com.agentcli.llm;

/**
 * Kimi（月之暗面 / Moonshot AI）客户端（OpenAI 兼容协议）。
 *
 * <p>直接复用 {@link OpenAiCompatibleClient} 的 HTTP / SSE / 工具闭环逻辑，
 * 仅覆写 url / model / apiKey 三个基础钩子，以及少量为 Kimi 定制的钩子：
 * <ul>
 *   <li>默认 base URL {@code https://api.moonshot.ai/v1}，默认模型 {@code kimi-k2.6}；</li>
 *   <li>provider key 为 {@code kimi}（别名 moonshot / moonshotai / moonshot-ai）；</li>
 *   <li>上下文窗口 256000；</li>
 *   <li>续轮需回带上轮 assistant 的 reasoning_content（{@code shouldSendReasoningContentInRequestHistory=true}）。</li>
 * </ul>
 *
 * <p>未引入图片多模态 / prompt caching 等参考实现能力（当前 {@link LlmClient} 接口无此能力）。
 */
public class KimiCompatibleClient extends OpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.moonshot.ai/v1";
    private static final String DEFAULT_MODEL = "kimi-k2.6";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public KimiCompatibleClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null);
    }

    public KimiCompatibleClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public KimiCompatibleClient(String apiKey, String model, String baseUrl) {
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
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "kimi";
    }

    @Override
    public int maxContextWindow() {
        return 256_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return PromptCacheMode.MOONSHOT;
    }
}
