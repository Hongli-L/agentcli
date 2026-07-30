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
    void createsKimiCompatibleClientFromKimiAliasWithCustomBaseUrl() {
        AppConfig config = configWith("kimi", "test-key", "kimi-k2", "https://api.example.com/v1");

        LlmClient client = LlmClientFactory.create("kimi", config);

        KimiCompatibleClient kimi = assertInstanceOf(KimiCompatibleClient.class, client);
        assertEquals("kimi", kimi.getProviderName());
        assertEquals("kimi-k2", kimi.getModelName());
        assertTrue(kimi.getApiUrl().endsWith("/chat/completions"), kimi.getApiUrl());
        assertEquals("https://api.example.com/v1/chat/completions", kimi.getApiUrl());
    }

    @Test
    void createsKimiCompatibleClientFromMoonshotAlias() {
        AppConfig config = configWith("kimi", "test-key", "kimi-k2.6", null);

        LlmClient client = LlmClientFactory.create("moonshot", config);

        KimiCompatibleClient kimi = assertInstanceOf(KimiCompatibleClient.class, client);
        assertEquals("kimi", kimi.getProviderName());
        assertEquals("kimi-k2.6", kimi.getModelName());
    }

    @Test
    void createsStepCompatibleClientFromStepAliasWithCustomBaseUrl() {
        AppConfig config = configWith("step", "test-key", "step-3.5-flash", "https://api.example.com/v1");

        LlmClient client = LlmClientFactory.create("step", config);

        StepCompatibleClient step = assertInstanceOf(StepCompatibleClient.class, client);
        assertEquals("step", step.getProviderName());
        assertEquals("step-3.5-flash", step.getModelName());
        assertTrue(step.getApiUrl().endsWith("/chat/completions"), step.getApiUrl());
        assertEquals("https://api.example.com/v1/chat/completions", step.getApiUrl());
    }

    @Test
    void createsGenericOpenAiClientFromOpenaiAlias() {
        AppConfig config = configWith("openai", "test-key", "gpt-4o", "https://api.example.com/v1");

        // 原 kimi/step 别名组已拆出，openai/oai/generic 仍归一到通用网关，确保 generic 路径不被破坏
        LlmClient oai = LlmClientFactory.create("oai", config);
        GenericOpenAiClient generic = assertInstanceOf(GenericOpenAiClient.class, oai);
        assertEquals("openai", generic.getProviderName());
        assertEquals("gpt-4o", generic.getModelName());

        LlmClient genericAlias = LlmClientFactory.create("generic", config);
        assertInstanceOf(GenericOpenAiClient.class, genericAlias);
    }

    @Test
    void returnsNullForUnknownProvider() {
        AppConfig config = configWith("zzz-unknown", "test-key", "x", null);
        assertNull(LlmClientFactory.create("zzz-unknown", config));
    }
}
