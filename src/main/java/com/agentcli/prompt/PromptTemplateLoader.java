package com.agentcli.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板加载器：统一从 classpath {@code prompts/<name>} 读取 UTF-8 模板。
 *
 * <p>设计约定：
 * <ul>
 *   <li>模板缺失或 IO 异常一律返回 {@code null}，<b>绝不抛异常</b>——调用方（如
 *       {@code SystemPrompt}）据此降级到硬编码文本，保证 CLI 永远可用。</li>
 *   <li>加载结果（含"未找到"）都会被缓存，避免每轮对话重复读 classpath。</li>
 *   <li>模板名做路径防御，拒绝 {@code ..} 与绝对路径，防止越权读取。</li>
 * </ul>
 */
public final class PromptTemplateLoader {

    /** classpath 下的模板根目录。 */
    private static final String TEMPLATE_ROOT = "prompts/";

    /**
     * 模板缓存。用 {@link Optional} 包裹是因为 {@link ConcurrentHashMap} 不允许 null value，
     * 而"该模板确实不存在"本身也是需要缓存的结论（否则每轮都会重试一次失败的 IO）。
     */
    private static final Map<String, Optional<String>> CACHE = new ConcurrentHashMap<>();

    private PromptTemplateLoader() {
    }

    /**
     * 加载模板内容。
     *
     * @param name 模板文件名，例如 {@code system_role.md}
     * @return 模板文本；缺失、名称非法或读取失败时返回 {@code null}
     */
    public static String load(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim();
        if (!isSafeName(key)) {
            return null;
        }
        return CACHE.computeIfAbsent(key, n -> Optional.ofNullable(resolve(n))).orElse(null);
    }

    /** 清空缓存，仅供测试与未来的模板热重载使用。 */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 真正的加载逻辑收口点：当前只读 classpath。
     *
     * @return 模板文本，读不到则返回 {@code null}
     */
    private static String resolve(String name) {
        // v0.17+ 覆盖点：可在此优先读 ~/.agentcli/prompts/<name>
        ClassLoader loader = PromptTemplateLoader.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(TEMPLATE_ROOT + name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // 模板读取失败不能影响主流程，交由调用方降级。
            return null;
        }
    }

    /** 路径防御：拒绝父目录穿越与绝对路径。 */
    private static boolean isSafeName(String name) {
        if (name.contains("..")) {
            return false;
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            return false;
        }
        // Windows 盘符形式的绝对路径，如 C:\x 或 C:/x
        return !(name.length() >= 2 && name.charAt(1) == ':');
    }
}
