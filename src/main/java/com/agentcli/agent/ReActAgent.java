package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.tool.ToolInvocation;
import com.agentcli.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent 核心：Thought → Action（tool calls）→ Observation → … → Final Answer。
 *
 * 一轮 {@link #run(String)} 的流程：
 * <ol>
 *   <li>把用户输入追加到对话历史</li>
 *   <li>调用 LLM（附带工具定义，支持流式增量回调）</li>
 *   <li>若返回 tool_calls：并行执行工具，把结果以 tool 角色消息写回历史，继续循环</li>
 *   <li>若返回纯文本：作为最终答复返回</li>
 * </ol>
 *
 * {@link AgentBudget} 在 token 用尽、检测到重复工具调用或超出硬轮数时强制收尾。
 */
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory = new ArrayList<>();
    private AgentRunListener listener = AgentRunListener.NO_OP;

    public ReActAgent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public ReActAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        resetHistory();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setListener(AgentRunListener listener) {
        this.listener = listener == null ? AgentRunListener.NO_OP : listener;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public List<LlmClient.Message> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }

    /** 清空对话历史并重建系统提示，不影响工具注册表配置。 */
    public void clearHistory() {
        resetHistory();
    }

    /**
     * 执行一轮用户请求，返回最终文本答复。
     *
     * 流式 reasoning/content 通过 {@link #setListener(AgentRunListener)} 回调；
     * 本方法返回值仅为模型最终 answer（不含已流式输出的 reasoning）。
     */
    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "";
        }
        log.info("ReAct run started: inputLength={}", userInput.length());
        conversationHistory.add(LlmClient.Message.user(userInput));

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String message = "❌ " + budget.describeExit(exitReason);
                log.warn("ReAct run stopped: {}", message);
                return message;
            }

            int iteration = budget.beginIteration();
            listener.onIterationStart(iteration);

            try {
                List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                        ? toolRegistry.getToolDefinitions()
                        : null;

                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolDefinitions,
                        listener
                );

                budget.recordTokens(
                        response.inputTokens(),
                        response.outputTokens(),
                        response.cachedInputTokens()
                );

                if (response.hasToolCalls()) {
                    log.info("LLM requested {} tool call(s) in iteration {}",
                            response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());

                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    listener.onToolCalls(response.toolCalls());
                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls());
                    for (ToolExecutionResult toolResult : toolResults) {
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                        listener.onToolResult(toolResult);
                    }
                    continue;
                }

                String answer = response.content() == null ? "" : response.content().trim();
                conversationHistory.add(LlmClient.Message.assistant(answer));
                log.info("ReAct run finished: iterations={}, inputTokens={}, outputTokens={}",
                        budget.iteration(), budget.totalInputTokens(), budget.totalOutputTokens());
                return answer;

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>(toolCalls.size());
        for (LlmClient.ToolCall toolCall : toolCalls) {
            invocations.add(new ToolInvocation(
                    toolCall.id(),
                    toolCall.function().name(),
                    toolCall.function().arguments()
            ));
        }
        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel", invocations.size());
        }
        return toolRegistry.executeTools(invocations);
    }

    private void resetHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(SystemPrompt.forAgent(toolRegistry)));
    }
}
