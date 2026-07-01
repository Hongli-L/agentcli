package com.agentcli.tool;

/**
 * 一次待执行的工具调用（通常来自 LLM 返回的 tool_call）。
 *
 * {@link #id} 与模型侧的 tool_call_id 对应，用于把执行结果按原顺序回灌消息历史。
 */
public record ToolInvocation(String id, String name, String argumentsJson) {
}
