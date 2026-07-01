package com.agentcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 Chat Completions 协议的模板基类。
 *
 * 用模板方法模式封装「构造请求体 -> 发起 SSE 流式请求 -> 增量解析 -> 聚合结果」的通用流程，
 * 子类只需提供 url / model / apiKey 三个钩子即可接入新模型（必要时再覆写少量 hook）。
 */
public abstract class OpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    // SSE 下 readTimeout 是「两次 read 之间的最大间隔」而非请求总时长；
    // 模型生成大段 reasoning 时服务端可能长时间静默，故放宽默认值，并允许系统属性覆盖。
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds("agentcli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds("agentcli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds("agentcli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds("agentcli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();

    /** DeepSeek 等带 reasoning 的模型续轮需要回带上一轮 assistant reasoning。 */
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    protected OkHttpClient httpClient() {
        return SHARED_HTTP_CLIENT;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    protected void customizeRequest(Request.Builder request) {
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools).toString(),
                MediaType.parse("application/json"));

        Request.Builder request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body);
        customizeRequest(request);

        try (Response response = httpClient().newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBody != null ? responseBody.string() : "无响应体";
                throw new IOException("API 请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBody == null) {
                throw new IOException("API 返回空响应体");
            }
            return parseStream(responseBody.source(), streamListener);
        }
    }

    private ChatResponse parseStream(BufferedSource source, StreamListener listener) throws IOException {
        String role = "assistant";
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedInputTokens = 0;
        String lastContentDelta = "";

        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }

            JsonNode root = MAPPER.readTree(payload);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IOException("API 请求失败: " + formatStreamingError(error));
            }

            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            if (delta.isMissingNode() || delta.isNull()) {
                delta = choice.path("message");
            }
            if (delta.isMissingNode() || delta.isNull()) {
                continue;
            }

            String deltaRole = delta.path("role").asText("");
            if (!deltaRole.isEmpty()) {
                role = deltaRole;
            }

            String reasoningDelta = extractReasoningDelta(delta);
            if (!reasoningDelta.isEmpty()) {
                reasoning.append(reasoningDelta);
                listener.onReasoningDelta(reasoningDelta);
            }

            String contentDelta = delta.path("content").asText("");
            if (!contentDelta.isEmpty() && !contentDelta.equals(lastContentDelta)) {
                content.append(contentDelta);
                listener.onContentDelta(contentDelta);
                lastContentDelta = contentDelta;
            }

            mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
        }

        List<ToolCall> toolCalls = buildToolCalls(toolAccumulators);
        if (content.isEmpty() && reasoning.isEmpty() && (toolCalls == null || toolCalls.isEmpty())) {
            throw new IOException("API 返回空内容，请检查 provider/model 配置或该模型是否支持当前请求参数");
        }
        return new ChatResponse(role, content.toString(), reasoning.toString(), toolCalls,
                inputTokens, outputTokens, cachedInputTokens);
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("model", getModel());
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content() == null ? "" : msg.content());

            if (shouldSendReasoningContentInRequestHistory()
                    && "assistant".equals(msg.role())
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }

        customizeRequestBody(requestBody);
        return requestBody;
    }

    private String formatStreamingError(JsonNode error) {
        String message = error.path("message").asText("");
        String code = error.path("code").asText("");
        if (!code.isEmpty() && !message.isEmpty()) {
            return code + " - " + message;
        }
        return message.isEmpty() ? error.toString() : message;
    }

    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        return delta.path("reasoning").asText("");
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }
        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }
            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }
            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(acc.id, new ToolCall.Function(acc.name.toString(), acc.arguments.toString())));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    /** 规范化 chat completions 端点：baseUrl 缺省回退默认值，并确保以 /chat/completions 结尾。 */
    protected static String resolveChatCompletionsUrl(String baseUrl, String defaultBaseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : defaultBaseUrl;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }

    private static long timeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
