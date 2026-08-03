package com.agentcli.rag;

import com.agentcli.common.AppConfig;

/**
 * 从 {@link AppConfig} 装配 {@link EmbeddingClient}。
 *
 * <p><b>永不返回 {@code null}</b>：配置不完整时返回 {@link EmbeddingClient#disabled(String)} 空对象，
 * 把「没配置」这件事变成一个可以正常调用 {@code isAvailable()} 的值，
 * 调用链上不需要任何 null 判断。
 */
public final class EmbeddingClientFactory {

    private EmbeddingClientFactory() {
    }

    /**
     * 按配置创建客户端。
     *
     * @param config 应用配置；{@code null} 时返回禁用客户端
     * @return 可用的客户端，或携带原因的禁用空对象
     */
    public static EmbeddingClient create(AppConfig config) {
        if (config == null) {
            return EmbeddingClient.disabled("未加载配置，embedding 不可用");
        }
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                config.getEmbeddingApiKey(),
                config.getEmbeddingBaseUrl(),
                config.getEmbeddingModel());
        if (!client.isAvailable()) {
            return EmbeddingClient.disabled(client.unavailableReason());
        }
        return client;
    }
}
