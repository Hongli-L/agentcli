package com.agentcli.llm;

import com.agentcli.common.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientFactoryTest {

    private static AppConfig configWith(String provider, String apiKey, String model, String baseUrl) {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig pc = new AppConfig.ProviderConfig();
        pc.setApiKey(apiKey);
        pc.setModel(model);
        pc.setBaseUrl(baseUrl);
        config.getProviders().put(provider, pc);
        return config;
    }

    @Test
    void createsGlmClient() {
        AppConfig config = configWith("glm", "test-glm-key", "glm-4-flash", null);

        LlmClient client = LlmClientFactory.create("glm", config);

        GlmClient glm = assertInstanceOf(GlmClient.class, client);
        assertEquals("glm", glm.getProviderName());
        assertEquals("glm-4-flash", glm.getModelName());
    }

    @Test
    void createsDeepSeekClient() {
        AppConfig config = configWith("deepseek", "test-ds-key", "deepseek-chat", null);

        LlmClient client = LlmClientFactory.create("deepseek", config);

        DeepSeekClient ds = assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek", ds.getProviderName());
        assertEquals(65_536, ds.maxContextWindow());
    }

    @Test
    void createsGenericOpenAiClientFromAliasWithCustomBaseUrl() {
        AppConfig config = configWith("openai", "test-key", "kimi-k2", "https://api.example.com/v1");

        LlmClient client = LlmClientFactory.create("kimi", config);

        GenericOpenAiClient generic = assertInstanceOf(GenericOpenAiClient.class, client);
        assertEquals("openai", generic.getProviderName());
        assertEquals("kimi-k2", generic.getModelName());
        assertTrue(generic.getApiUrl().endsWith("/chat/completions"), generic.getApiUrl());
        assertEquals("https://api.example.com/v1/chat/completions", generic.getApiUrl());
    }

    @Test
    void returnsNullForUnknownProvider() {
        AppConfig config = configWith("zzz-unknown", "test-key", "x", null);
        assertNull(LlmClientFactory.create("zzz-unknown", config));
    }
}
