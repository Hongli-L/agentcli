package com.agentcli.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 应用级配置。
 *
 * 持久化到 {@code ~/.agentcli/config.json}，缺省值回退到环境变量 / .env。
 * 仅保留 ReAct CLI 必需的多模型接入字段：默认 provider 及各 provider 的 key/model/baseUrl。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    public static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".agentcli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** embedding 未配置 baseUrl 时的默认端点（OpenAI 官方）。 */
    private static final String DEFAULT_EMBEDDING_BASE_URL = "https://api.openai.com/v1";
    /** embedding 未配置 model 时的默认模型（1536 维，性价比与召回均衡）。 */
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    private String defaultProvider = "glm";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private EmbeddingConfig embedding = new EmbeddingConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private String loraId;

        public ProviderConfig() {
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        /** 微调模型标识（当前用于讯飞 MaaS 的 {@code lora_id} 请求头），未使用时为 {@code null}。 */
        public String getLoraId() {
            return loraId;
        }

        public void setLoraId(String loraId) {
            this.loraId = loraId;
        }
    }

    /**
     * v0.18.0：RAG 代码索引使用的 embedding 配置段。
     *
     * <p>与 {@link ProviderConfig} 分开而不是塞进 {@code providers} map，是因为 embedding 与
     * chat 是两类模型：用户完全可能 chat 走 GLM、embedding 走本地 Ollama，混在一起会让
     * {@code /model} 的切换语义变得含混。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingConfig {
        private String apiKey;
        private String baseUrl;
        private String model;

        public EmbeddingConfig() {
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public EmbeddingConfig getEmbedding() {
        return embedding;
    }

    public void setEmbedding(EmbeddingConfig embedding) {
        this.embedding = embedding == null ? new EmbeddingConfig() : embedding;
    }

    /**
     * embedding API Key，回退到 {@code EMBEDDING_API_KEY} 环境变量。
     *
     * <p>刻意<b>不造默认值</b>：缺 key 时返回 {@code null}，由上层判定为「不可用」并降级到
     * 关键词检索，而不是拿一个假 key 去打必然失败的请求。
     */
    public String getEmbeddingApiKey() {
        if (embedding != null && isPresent(embedding.getApiKey())) {
            return embedding.getApiKey();
        }
        return EnvLoader.get("EMBEDDING_API_KEY");
    }

    /** embedding 端点，回退顺序：配置 → {@code EMBEDDING_BASE_URL} → OpenAI 官方端点。 */
    public String getEmbeddingBaseUrl() {
        if (embedding != null && isPresent(embedding.getBaseUrl())) {
            return embedding.getBaseUrl();
        }
        String fromEnv = EnvLoader.get("EMBEDDING_BASE_URL");
        return isPresent(fromEnv) ? fromEnv : DEFAULT_EMBEDDING_BASE_URL;
    }

    /** embedding 模型名，回退顺序：配置 → {@code EMBEDDING_MODEL} → {@code text-embedding-3-small}。 */
    public String getEmbeddingModel() {
        if (embedding != null && isPresent(embedding.getModel())) {
            return embedding.getModel();
        }
        String fromEnv = EnvLoader.get("EMBEDDING_MODEL");
        return isPresent(fromEnv) ? fromEnv : DEFAULT_EMBEDDING_MODEL;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public String getApiKey(String provider) {
        ProviderConfig config = providers.get(provider);
        if (config != null && isPresent(config.getApiKey())) {
            return config.getApiKey();
        }
        return EnvLoader.get(envKey(provider, "API_KEY"));
    }

    public String getModel(String provider) {
        ProviderConfig config = providers.get(provider);
        if (config != null && isPresent(config.getModel())) {
            return config.getModel();
        }
        return EnvLoader.get(envKey(provider, "MODEL"));
    }

    public String getBaseUrl(String provider) {
        ProviderConfig config = providers.get(provider);
        if (config != null && isPresent(config.getBaseUrl())) {
            return config.getBaseUrl();
        }
        return EnvLoader.get(envKey(provider, "BASE_URL"));
    }

    /** 微调模型标识（如讯飞 MaaS 的 lora_id），回退到 {@code <PROVIDER>_LORA_ID} 环境变量。 */
    public String getLoraId(String provider) {
        ProviderConfig config = providers.get(provider);
        if (config != null && isPresent(config.getLoraId())) {
            return config.getLoraId();
        }
        return EnvLoader.get(envKey(provider, "LORA_ID"));
    }

    public static AppConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return MAPPER.readValue(CONFIG_FILE.toFile(), AppConfig.class);
            } catch (IOException e) {
                log.warn("配置文件读取失败，使用默认配置: {}", e.getMessage());
            }
        }
        return new AppConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            MAPPER.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            log.warn("配置保存失败: {}", e.getMessage());
        }
    }

    private static String envKey(String provider, String suffix) {
        String normalized = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        return normalized + "_" + suffix;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
