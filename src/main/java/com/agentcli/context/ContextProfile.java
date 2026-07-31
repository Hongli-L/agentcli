package com.agentcli.context;

import com.agentcli.common.AnsiStyle;
import com.agentcli.llm.LlmClient;
import com.agentcli.mcp.McpServerManager;
import com.agentcli.memory.MemoryManager;
import com.agentcli.memory.TokenBudget;
import com.agentcli.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 当前会话的上下文画像：一次性把"我是谁、挂了多少能力、烧了多少 token"聚合成不可变快照。
 *
 * <p>约定：唯一构建入口是 {@link #capture}，即采即用<b>不做缓存</b>——画像里的
 * token 字段每轮都在变，缓存只会让 {@code /status} 显示过期数据。
 *
 * <p>所有采集源都按 nullable 处理（MCP 未启用、记忆未初始化都是正常状态），
 * 采集过程不抛异常，保证 REPL 命令永远有东西可打印。
 *
 * @param provider         LLM 提供方标识，如 deepseek/glm
 * @param model            模型名
 * @param projectPath      项目根目录
 * @param toolCount        已注册工具数
 * @param mcpServerCount   已配置的 MCP 服务器数
 * @param memoryEntryCount 长期记忆条数
 * @param llmCallCount     会话内 LLM 调用次数
 * @param usedInputTokens  累计输入 token
 * @param usedOutputTokens 累计输出 token
 * @param cachedInputTokens 累计缓存命中输入 token
 * @param contextWindow    模型上下文窗口大小
 */
public record ContextProfile(
        String provider,
        String model,
        String projectPath,
        int toolCount,
        int mcpServerCount,
        int memoryEntryCount,
        int llmCallCount,
        int usedInputTokens,
        int usedOutputTokens,
        int cachedInputTokens,
        int contextWindow
) {

    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /**
     * 采集当前会话画像。任一数据源为 null 或抛错，对应字段取安全默认值。
     *
     * @param llmClient     LLM 客户端（可为 null）
     * @param toolRegistry  工具注册表（可为 null）
     * @param mcpManager    MCP 管理器（可为 null，未启用 MCP 时计 0）
     * @param memoryManager 记忆管理器（可为 null）
     */
    public static ContextProfile capture(LlmClient llmClient,
                                         ToolRegistry toolRegistry,
                                         McpServerManager mcpManager,
                                         MemoryManager memoryManager) {
        String provider = safeString(() -> llmClient == null ? "unknown" : llmClient.getProviderName(), "unknown");
        String model = safeString(() -> llmClient == null ? "unknown" : llmClient.getModelName(), "unknown");
        String projectPath = safeString(() -> toolRegistry == null ? "." : toolRegistry.getProjectPath(), ".");

        int toolCount = safeInt(() -> toolRegistry == null ? 0 : toolRegistry.getToolDefinitions().size());
        int mcpServerCount = safeInt(() -> mcpManager == null ? 0 : mcpManager.servers().size());
        int memoryEntryCount = safeInt(() -> memoryManager == null ? 0 : memoryManager.listLongTerm().size());

        TokenBudget budget = memoryManager == null ? null : safeBudget(memoryManager);
        int llmCallCount = budget == null ? 0 : safeInt(budget::getLlmCallCount);
        int usedInput = budget == null ? 0 : safeInt(budget::getTotalInputTokens);
        int usedOutput = budget == null ? 0 : safeInt(budget::getTotalOutputTokens);
        int cachedInput = budget == null ? 0 : safeInt(budget::getTotalCachedInputTokens);

        int window = budget == null
                ? safeInt(() -> llmClient == null ? DEFAULT_CONTEXT_WINDOW : llmClient.maxContextWindow())
                : safeInt(budget::getContextWindow);
        if (window <= 0) {
            window = DEFAULT_CONTEXT_WINDOW;
        }

        return new ContextProfile(provider, model, projectPath, toolCount, mcpServerCount,
                memoryEntryCount, llmCallCount, usedInput, usedOutput, cachedInput, window);
    }

    /** 累计消耗的 token（输入 + 输出）。 */
    public int totalUsedTokens() {
        return usedInputTokens + usedOutputTokens;
    }

    /**
     * 上下文窗口占用率，取值 0~1（超出窗口时封顶为 1）。
     */
    public double windowUsageRatio() {
        if (contextWindow <= 0) {
            return 0.0;
        }
        double ratio = (double) totalUsedTokens() / contextWindow;
        if (ratio < 0.0) {
            return 0.0;
        }
        return Math.min(ratio, 1.0);
    }

    /**
     * 供系统提示词使用的模板变量。
     *
     * <p>只暴露<b>低频稳定</b>字段：token 用量之类每轮都变的数值一旦进入系统提示，
     * 会让上游的 prompt cache 每轮失效，得不偿失。
     */
    public Map<String, String> toTemplateVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("tool_count", String.valueOf(toolCount));
        vars.put("project_path", projectPath == null ? "." : projectPath);
        vars.put("model_name", model == null ? "" : model);
        return vars;
    }

    /** 渲染 {@code /status} 的多行文本（带 ANSI 着色，NO_COLOR 下自动降级为纯文本）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.section("会话状态")).append('\n');
        sb.append(AnsiStyle.subtle("  模型      : ")).append(provider).append(" / ").append(model).append('\n');
        sb.append(AnsiStyle.subtle("  项目      : ")).append(projectPath).append('\n');
        sb.append(AnsiStyle.subtle("  工具      : ")).append(toolCount).append(" 个已注册").append('\n');
        sb.append(AnsiStyle.subtle("  MCP       : ")).append(mcpServerCount).append(" 个服务器").append('\n');
        sb.append(AnsiStyle.subtle("  长期记忆  : ")).append(memoryEntryCount).append(" 条").append('\n');
        sb.append(AnsiStyle.subtle("  LLM 调用  : ")).append(llmCallCount).append(" 次").append('\n');
        sb.append(AnsiStyle.subtle("  Token     : "))
                .append(String.format(Locale.ROOT, "输入 %,d / 输出 %,d / 缓存 %,d",
                        usedInputTokens, usedOutputTokens, cachedInputTokens))
                .append('\n');

        String usage = String.format(Locale.ROOT, "%,d / %,d (%.1f%%)",
                totalUsedTokens(), contextWindow, windowUsageRatio() * 100);
        sb.append(AnsiStyle.subtle("  窗口占用  : "))
                .append(windowUsageRatio() >= 0.8 ? AnsiStyle.error(usage) : usage);
        return sb.toString();
    }

    private static String safeString(ValueSupplier<String> supplier, String fallback) {
        try {
            String value = supplier.get();
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int safeInt(ValueSupplier<Integer> supplier) {
        try {
            Integer value = supplier.get();
            return value == null ? 0 : Math.max(0, value);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static TokenBudget safeBudget(MemoryManager memoryManager) {
        try {
            return memoryManager.getTokenBudget();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 采集用的取值函数，允许抛出运行时异常由调用方兜底。 */
    @FunctionalInterface
    private interface ValueSupplier<T> {
        T get();
    }
}
