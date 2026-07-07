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

    private String defaultProvider = "glm";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;

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
