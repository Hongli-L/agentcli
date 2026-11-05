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

    /**
     * 对话消息。除纯文本外，user 角色可携带多模态内容部件（{@link ContentPart}），
     * 如图片附件——用于「贴图让 agent 看」的场景。
     */
    record Message(String role, String content, String reasoningContent,
                   List<ToolCall> toolCalls, String toolCallId, List<ContentPart> parts) {

        public Message(String role, String content) {
            this(role, content, null, null, null, null);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        /** 多模态用户消息：文本 + 图片部件由上层（ImageMentionParser）组装。 */
        public static Message user(List<ContentPart> parts) {
            return new Message("user", null, null, null, null, parts);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null, null);
        }

        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId, null);
        }

        /** 是否携带图片部件（upload 时决定是否用多部分内容数组）。 */
        public boolean hasImageParts() {
            return parts != null && parts.stream().anyMatch(ContentPart::isImage);
        }

        /** 提取纯文本：优先拼接文本部件，否则回退 content。 */
        public String textContent() {
            if (parts != null && !parts.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ContentPart p : parts) {
                    if (!p.isImage() && p.text() != null) {
                        sb.append(p.text());
                    }
                }
                return sb.toString();
            }
            return content == null ? "" : content;
        }

        /**
         * 转为纯文本版本：丢弃图片部件（仅保留文本），供历史落盘复用——
         * 避免每轮对话都把同一张 base64 图片重新发送给模型（既贵又撑爆上下文）。
         */
        public Message toTextOnly() {
            if (!hasImageParts()) {
                return this;
            }
            return new Message(role, textContent(), reasoningContent, toolCalls, toolCallId, null);
        }

        /** 判断本轮是否为空（无文本且无图片）。 */
        public boolean isBlankTurn() {
            if (hasImageParts()) {
                return false;
            }
            String t = content == null ? "" : content;
            return t.isBlank();
        }
    }

    /**
     * 消息内容部件：支持文本与图片。图片以 data URI（{@code data:<mime>;base64,<b64>}）形式携带，
     * 可直接映射到 OpenAI 兼容 vision 协议的 {@code image_url.url} 字段。
     */
    record ContentPart(String type, String text, String imageUrl, String mimeType) {

        public static ContentPart text(String text) {
            return new ContentPart("text", text, null, null);
        }

        public static ContentPart imageUrl(String url) {
            return new ContentPart("image_url", null, url, null);
        }

        public static ContentPart imageBase64(String base64, String mimeType) {
            String safeMime = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
            String url = "data:" + safeMime + ";base64," + base64;
            return new ContentPart("image_url", null, url, safeMime);
        }

        public boolean isImage() {
            return "image_url".equals(type);
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
