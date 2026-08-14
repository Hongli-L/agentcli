package com.agentcli.agent;

import com.agentcli.agent.concurrent.AgentTaskExecutor;
import com.agentcli.agent.concurrent.CancellationScope;
import com.agentcli.llm.LlmClient;
import com.agentcli.common.SandboxPolicy;
import com.agentcli.common.ToolCallQuota;
import com.agentcli.common.UserFacingErrors;
import com.agentcli.context.ContextProfile;
import com.agentcli.memory.HybridMemoryCompressor;
import com.agentcli.skill.SkillIndexFormatter;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.memory.MemoryManager;
import com.agentcli.memory.TokenBudget;
import com.agentcli.prompt.ApprovalMode;
import com.agentcli.prompt.PromptMode;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.tool.ToolInvocation;
import com.agentcli.tool.ToolRegistry;
import com.agentcli.lsp.LspContextInjector;
import com.agentcli.tool.builtin.SaveMemoryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent 核心：Thought → Action（tool calls）→ Observation → … → Final Answer。
 *
 * <p><b>v0.20.0 · 取消传播第一层（协作式）</b>：在两个安全点轮询
 * {@link CancellationScope#isCancelled()}——每轮循环开头、以及工具批次派发之前。
 * 命中即返回统一文案 {@link AgentTaskExecutor#CANCELLED_RESULT}，
 * 不抛异常：取消是<b>正常控制流</b>，让编排层能把它当作一次「有结果的任务」聚合进最终报告。
 *
 * <p>未绑定令牌时 {@code isCancelled()} 恒为 {@code false}，普通 REPL 单轮对话与既有回归
 * 用例走的仍是一字未改的老路径。
 */
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final HybridMemoryCompressor hybridCompactor;
    private String lastUserQuery;
    private final List<LlmClient.Message> conversationHistory = new ArrayList<>();
    private AgentRunListener listener = AgentRunListener.NO_OP;
    private SkillRegistry skillRegistry;
    private LspContextInjector lspInjector;
    private PromptMode promptMode = PromptMode.DEFAULT;
    private ApprovalMode approvalMode = ApprovalMode.SUGGEST;

    public ReActAgent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public ReActAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = new MemoryManager(llmClient);
        this.hybridCompactor = new HybridMemoryCompressor(llmClient, memoryManager.getRetriever());
        this.memoryManager.setProjectPath(toolRegistry.getProjectPath());
        this.toolRegistry.register(new SaveMemoryTool(memoryManager));
        resetHistory();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        memoryManager.setLlmClient(llmClient);
        hybridCompactor.setLlmClient(llmClient);
    }

    public void setListener(AgentRunListener listener) {
        this.listener = listener == null ? AgentRunListener.NO_OP : listener;
    }

    /** 注入技能注册表，使系统提示可注入已启用 skill 的索引段。 */
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 注入 LSP 诊断回灌钩子（可选，为 null 时完全不干预主流程）。
     */
    public void setLspInjector(LspContextInjector injector) {
        this.lspInjector = injector;
    }

    /**
     * 设置系统提示词的<b>行为模式</b>维度（default/plan/review）。该维度稳定于一次会话内，
     * 位于 Prompt Cache 前缀中段；调用后下一次 {@link #run} 起的系统提示即生效。
     */
    public void setPromptMode(PromptMode promptMode) {
        this.promptMode = promptMode == null ? PromptMode.DEFAULT : promptMode;
    }

    public PromptMode getPromptMode() {
        return promptMode;
    }

    /**
     * 设置系统提示词的<b>审批策略</b>维度（suggest/auto/never）。与 {@link #setPromptMode} 对称。
     */
    public void setApprovalMode(ApprovalMode approvalMode) {
        this.approvalMode = approvalMode == null ? ApprovalMode.SUGGEST : approvalMode;
    }

    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public List<LlmClient.Message> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }

    public void clearHistory() {
        resetHistory();
    }

    public CompactionResult compactHistoryNow() {
        int beforeTokens = TokenBudget.estimateMessagesTokens(conversationHistory);
        try {
            boolean compacted = hybridCompactor.compactNow(conversationHistory, lastUserQuery, memoryManager.getCurrentProjectKey());
            int afterTokens = TokenBudget.estimateMessagesTokens(conversationHistory);
            return new CompactionResult(compacted, beforeTokens, afterTokens, null);
        } catch (Exception e) {
            return new CompactionResult(false, beforeTokens, beforeTokens, e.getMessage());
        }
    }

    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "";
        }
        log.info("ReAct run started: inputLength={}", userInput.length());
        this.lastUserQuery = userInput;
        memoryManager.setProjectPath(toolRegistry.getProjectPath());
        updateSystemPrompt(userInput);
        conversationHistory.add(LlmClient.Message.user(userInput));

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            // 取消安全点 ①：每轮循环开头。此时上一轮的 LLM 调用与工具批次都已收束，
            // 直接返回不会留下半截的 tool_call 悬空在历史里。
            if (CancellationScope.isCancelled()) {
                log.info("ReAct run cancelled at iteration boundary: iteration={}", budget.iteration());
                return AgentTaskExecutor.CANCELLED_RESULT;
            }

            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String message = UserFacingErrors.fail(budget.describeExit(exitReason));
                log.warn("ReAct run stopped: {}", message);
                return message;
            }

            int iteration = budget.beginIteration();
            listener.onIterationStart(iteration);
            maybeCompactHistory();

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

                    // 取消安全点 ②：工具批次派发之前。工具可能是 write_file / execute_command
                    // 这类有副作用的操作，已取消就绝不能再放出去。
                    if (CancellationScope.isCancelled()) {
                        log.info("ReAct run cancelled before tool batch: iteration={}", iteration);
                        return AgentTaskExecutor.CANCELLED_RESULT;
                    }

                    listener.onToolCalls(response.toolCalls());
                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls());
                    // LSP 诊断回灌钩子：在结果写入对话历史之前注入（null 时零干预）
                    if (lspInjector != null) {
                        try {
                            toolResults = lspInjector.afterToolResults(toolResults);
                        } catch (Exception e) {
                            log.warn("LSP 诊断注入失败，已忽略：{}", e.getMessage());
                        }
                    }
                    for (ToolExecutionResult toolResult : toolResults) {
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                        listener.onToolResult(toolResult);
                    }
                    continue;
                }

                String answer = response.content() == null ? "" : response.content().trim();
                conversationHistory.add(LlmClient.Message.assistant(answer));
                memoryManager.recordTokenUsage(
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        budget.totalCachedInputTokens()
                );
                log.info("ReAct run finished: iterations={}, inputTokens={}, outputTokens={}",
                        budget.iteration(), budget.totalInputTokens(), budget.totalOutputTokens());
                return answer;

            } catch (IOException e) {
                // Call.cancel() 打断 SSE 时会以 IOException 冒泡，这是主动取消而非网络故障，
                // 不能报成 "LLM 调用失败" 污染用户视野。
                if (CancellationScope.isCancelled()) {
                    log.info("ReAct run cancelled during LLM call: iteration={}", iteration);
                    return AgentTaskExecutor.CANCELLED_RESULT;
                }
                log.error("LLM call failed in ReAct loop", e);
                return UserFacingErrors.llmFailure(e.getMessage());
            }
        }
    }

    private void updateSystemPrompt(String userQuery) {
        String memoryContext = memoryManager.buildContextForQuery(userQuery);
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        // 只为模板变量（tool_count/model_name）采集画像；token 等高频字段不会进入系统提示，避免击穿 prompt cache。
        ContextProfile profile = ContextProfile.capture(llmClient, toolRegistry, null, memoryManager);
        StringBuilder prompt = new StringBuilder(
                SystemPrompt.forAgent(toolRegistry, memoryContext, profile, promptMode, approvalMode));
        if (skillRegistry != null) {
            String index = SkillIndexFormatter.format(skillRegistry.enabledSkills());
            if (!index.isBlank()) {
                prompt.append("\n\n").append(index);
            }
        }
        return prompt.toString();
    }

    private void maybeCompactHistory() {
        try {
            int trigger = memoryManager.getTokenBudget().compressionTriggerTokens();
            if (hybridCompactor.compactIfNeeded(conversationHistory, trigger, lastUserQuery, memoryManager.getCurrentProjectKey())) {
                log.info("Conversation history compacted before LLM call");
            }
        } catch (Exception e) {
            log.warn("conversation history compaction failed", e);
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
        // 单轮工具调用配额：防止一次 LLM 响应扇出过量调用（资源耗尽 / runaway）。
        SandboxPolicy policy = toolRegistry.sandboxPolicy();
        ToolCallQuota quota = policy.newQuota();
        List<ToolInvocation> allowed = new ArrayList<>(invocations.size());
        for (ToolInvocation invocation : invocations) {
            if (quota.tryConsume()) {
                allowed.add(invocation);
            }
        }
        List<ToolExecutionResult> allowedResults = allowed.isEmpty()
                ? List.of()
                : toolRegistry.executeTools(allowed);
        int ai = 0;
        List<ToolExecutionResult> results = new ArrayList<>(invocations.size());
        for (ToolInvocation invocation : invocations) {
            if (allowed.contains(invocation)) {
                results.add(allowedResults.get(ai++));
            } else {
                results.add(new ToolExecutionResult(
                        invocation.id(),
                        invocation.name(),
                        invocation.argumentsJson(),
                        "🛡️ 单轮工具调用超过上限 " + quota.max()
                                + "，已忽略此调用（请拆成多轮分步执行）",
                        0,
                        false));
            }
        }
        return results;
    }

    private void resetHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    public record CompactionResult(boolean compacted, int beforeTokens, int afterTokens, String error) {
    }
}
