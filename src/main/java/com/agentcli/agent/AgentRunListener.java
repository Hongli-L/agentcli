package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.tool.ToolInvocation;
import com.agentcli.tool.ToolRegistry;

import java.util.List;

/**
 * ReAct 运行期事件监听器：流式增量、迭代与工具执行回调。
 *
 * CLI 层（C7）可接入实现终端渲染；测试可记录调用序列做断言。
 */
public interface AgentRunListener extends LlmClient.StreamListener {

    AgentRunListener NO_OP = new AgentRunListener() {
    };

    default void onIterationStart(int iteration) {
    }

    default void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
    }

    default void onToolResult(ToolExecutionResult result) {
    }

    /**
     * 单轮运行收尾时回调：本回合的 token 消耗与墙钟耗时。
     * 默认空实现，渲染层可据此打印一行小结。
     */
    default void onTurnSummary(TurnUsage usage) {
    }
}
