package com.agentcli.web;

import com.agentcli.common.EnvLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 按环境变量 / .env / 系统属性选择 {@link SearchProvider} 实现。
 *
 * 未显式 {@code SEARCH_PROVIDER} 时的自动选择优先级：
 * <ol>
 *   <li>有 {@code GLM_API_KEY} → zhipu（智谱 Web Search，与 GLM 推理共用 Key，国内首选）</li>
 *   <li>有 {@code SERPAPI_KEY} → serpapi（国际通用，付费即开即用）</li>
 *   <li>有 {@code SEARXNG_URL} → searxng（开源自托管，免费）</li>
 *   <li>都没有 → 占位 zhipu provider，{@link SearchProvider#isReady()} 为 false，由调用方提示用户</li>
 * </ol>
 *
 * 显式 {@code SEARCH_PROVIDER}（zhipu / serpapi / searxng）会跳过自动判断。
 * 不做单例缓存，由调用方按需缓存。
 */
public final class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    private SearchProviderFactory() {
    }

    public static SearchProvider create() {
        String provider = EnvLoader.get("SEARCH_PROVIDER");
        String glmKey = EnvLoader.get("GLM_API_KEY");
        String zhipuEngine = EnvLoader.get("ZHIPU_SEARCH_ENGINE");
        String serpKey = EnvLoader.get("SERPAPI_KEY");
        String searxngUrl = EnvLoader.get("SEARXNG_URL");

        String chosen = pickProvider(provider, glmKey, serpKey, searxngUrl);
        log.info("SearchProvider chosen: {}", chosen);

        return switch (chosen) {
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            case "serpapi" -> new SerpApiSearchProvider(serpKey);
            default -> new ZhipuSearchProvider(glmKey, zhipuEngine);
        };
    }

    static String pickProvider(String explicit, String glmKey, String serpKey, String searxngUrl) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (glmKey != null && !glmKey.isBlank()) {
            return "zhipu";
        }
        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }
        // 默认占位（AgentCLI 默认面向 GLM 用户），isReady() 会为 false 触发优雅降级
        return "zhipu";
    }
}
