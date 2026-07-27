package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import com.agentcli.common.UserFacingErrors;
import com.agentcli.memory.ConversationHistoryCompactor;
import com.agentcli.skill.SkillIndexFormatter;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.memory.MemoryManager;
import com.agentcli.memory.TokenBudget;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.tool.ToolInvocation;
import com.agentcli.tool.ToolRegistry;
import com.agentcli.tool.builtin.SaveMemoryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent 核心：Thought → Action（tool calls）→ Observation → … → Final Answer。
 */
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final List<LlmClient.Message> conversationHistory = new ArrayList<>();
    private AgentRunListener listener = AgentRunListener.NO_OP;
    private SkillRegistry skillRegistry;

    public ReActAgent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public ReActAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.memoryManager.setProjectPath(toolRegistry.getProjectPath());
        this.toolRegistry.register(new SaveMemoryTool(memoryManager));
        resetHistory();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        memoryManager.setLlmClient(llmClient);
        historyCompactor.setLlmClient(llmClient);
    }

    public void setListener(AgentRunListener listener) {
        this.listener = listener == null ? AgentRunListener.NO_OP : listener;
    }

    /** 注入技能注册表，使系统提示可注入已启用 skill 的索引段。 */
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
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
            boolean compacted = historyCompactor.compactNow(conversationHistory);
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
        memoryManager.setProjectPath(toolRegistry.getProjectPath());
        updateSystemPrompt(userInput);
        conversationHistory.add(LlmClient.Message.user(userInput));

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
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
                memoryManager.recordTokenUsage(
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        budget.totalCachedInputTokens()
                );
                log.info("ReAct run finished: iterations={}, inputTokens={}, outputTokens={}",
                        budget.iteration(), budget.totalInputTokens(), budget.totalOutputTokens());
                return answer;

            } catch (IOException e) {
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
        StringBuilder prompt = new StringBuilder(SystemPrompt.forAgent(toolRegistry, memoryContext));
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
            if (historyCompactor.compactIfNeeded(conversationHistory, trigger)) {
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
        return toolRegistry.executeTools(invocations);
    }

    private void resetHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    public record CompactionResult(boolean compacted, int beforeTokens, int afterTokens, String error) {
    }
}
