package com.agentcli.agent;

/**
 * 单轮 Agent 运行的用量与耗时小结。
 *
 * <p>由 {@link ReActAgent} 在每轮 {@code run} 收尾时构造并交给 {@link AgentRunListener}，
 * 供终端渲染「本回合消耗」一行。token 字段是该轮内 {@code AgentBudget} 的累计值
 * （一个 {@code run} 调用 = 一次用户提问对应的完整 ReAct 循环）。
 *
 * @param provider           LLM 提供方标识（deepseek/glm…）
 * @param model              模型名
 * @param inputTokens        本回合输入 token
 * @param outputTokens       本回合输出 token
 * @param cachedInputTokens  本回合命中 prompt cache 的输入 token
 * @param elapsedMillis      本回合墙钟耗时（毫秒）
 */
public record TurnUsage(
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        int cachedInputTokens,
        long elapsedMillis
) {
}
