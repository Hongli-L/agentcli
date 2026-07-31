package com.agentcli.llm;

/**
 * Prompt Cache（提示词缓存）模式常量。
 *
 * <p>各厂商的提示词缓存机制命名与生效条件互不相同，但对 AgentCLI 而言只需要一个
 * 「可展示、可归档」的稳定标识：用于 {@code /status} 展示与成本报表里区分缓存命中口径。
 *
 * <p>注意：本类只承载元数据语义，<b>不</b>参与任何 HTTP 头注入或请求体改写——
 * 各厂商的缓存均由服务端自动生效（或由厂商控制台配置），客户端无需自描述头。
 */
public final class PromptCacheMode {

    /** 不支持 / 未启用提示词缓存。 */
    public static final String NONE = "none";

    /** Moonshot（Kimi）Context Cache。 */
    public static final String MOONSHOT = "moonshot-context-cache";

    /** StepFun 前缀缓存。 */
    public static final String STEP = "step-prefix-cache";

    /** 智谱 GLM 提示词缓存。 */
    public static final String GLM = "glm-prompt-cache";

    /** DeepSeek 硬盘自动前缀缓存。 */
    public static final String DEEPSEEK = "automatic-prefix-cache";

    private PromptCacheMode() {
    }
}
