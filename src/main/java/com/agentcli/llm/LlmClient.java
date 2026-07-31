package com.agentcli.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/**
 * 统一的 LLM 调用抽象。
 *
 * 屏蔽不同厂商（GLM / DeepSeek / 任意 OpenAI 兼容网关）的接入差异，
 * 对上层 Agent 只暴露一个面向「消息 + 工具」的 chat 接口与流式回调。
 */
public interface LlmClient {

    /** 非流式调用（内部仍走流式协议，只是不回调增量）。 */
    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    /** 流式调用，增量通过 {@link StreamListener} 回调。 */
    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    String getModelName();

    String getProviderName();

    /** 模型上下文窗口（token），用于记忆压缩与预算估算。 */
    default int maxContextWindow() {
        return 128_000;
    }

    /** 是否支持 function calling / tools。 */
    default boolean supportsTools() {
        return true;
    }

    /** 该 provider 是否具备提示词缓存（prompt cache）能力。纯元数据，仅用于展示与成本口径。 */
    default boolean supportsPromptCaching() {
        return false;
    }

    /** 提示词缓存模式标识，取值见 {@link PromptCacheMode}。 */
    default String promptCacheMode() {
        return PromptCacheMode.NONE;
    }

    /** 对话消息。text-only：仅承载文本内容与工具调用协议字段。 */
    record Message(String role, String content, String reasoningContent,
                   List<ToolCall> toolCalls, String toolCallId) {

        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId);
        }
    }

    /** 模型请求的一次工具调用。 */
    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {
        }
    }

    /** 提供给模型的工具定义。 */
    record Tool(String name, String description, JsonNode parameters) {
    }

    /** 流式增量监听器。 */
    interface StreamListener {
        StreamListener NO_OP = new StreamListener() {
        };

        default void onReasoningDelta(String delta) {
        }

        default void onContentDelta(String delta) {
        }
    }

    /** 一次 chat 的完整结果。 */
    record ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                        int inputTokens, int outputTokens, int cachedInputTokens) {

        public ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, reasoningContent, toolCalls, inputTokens, outputTokens, 0);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
