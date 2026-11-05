package com.agentcli.llm;

import com.agentcli.agent.concurrent.CancellationScope;
import com.agentcli.agent.concurrent.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
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
 *
 * <p><b>v0.20.0 · 取消传播第三层（句柄级）</b>：这里持有 {@link Call} 句柄并注册到当前线程的
 * {@link CancellationToken} 上，{@code finally} 必须注销钩子，否则 token 会一直强引用
 * 已完成的 Call（内存泄漏）。
 *
 * <p><b>为什么有了线程中断还需要这一层</b>（实测结论，勿按旧经验回改）：流传甚广的说法是
 * 「{@code Thread.interrupt()} 唤不醒阻塞在 socket 上的读」——这在 JDK 13+ 已经<b>不成立</b>。
 * JDK 13 起 {@code NioSocketImpl} 取代了旧的 {@code PlainSocketImpl}，实测（JDK 17 / Windows 11）
 * 对阻塞在 SSE 读取上的线程 interrupt，百毫秒级即抛出 {@link java.io.InterruptedIOException}。
 * 所以 L3 的价值<b>不是「唯一能中止」，而是「让中止语义可识别」</b>：单靠中断拿到的
 * {@code InterruptedIOException} 与真实网络抖动长得一模一样，上层只能报「LLM 调用失败」；
 * 唯有走 {@link Call#cancel()} 才能确定性地转成 {@link #CANCELLED_MESSAGE}，
 * 让用户看到「已取消」而不是一个疑似故障。对照实证见 {@code LlmCancellationTest}。
 *
 * <p>{@link CancellationScope#current()} 为 {@code null} 时（普通 REPL 单轮对话、
 * 全部历史单元测试）走完全相同的老路径，零侵入。
 */
public abstract class OpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** 取消后统一抛出的异常消息，上层据此识别「用户主动取消」而非「网络故障」。 */
    static final String CANCELLED_MESSAGE = "已取消";

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

    /**
     * SSE 分片级钩子：在通用 {@code error} 节点检查之后、usage 解析之前调用。
     *
     * <p>供在 HTTP 200 响应体内用业务码表达错误的厂商（如讯飞的 {@code code} 字段）覆写，
     * 默认不做任何事。抛出 {@link IOException} 即可中断本次流式解析。
     */
    protected void inspectStreamChunk(JsonNode root) throws IOException {
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

        CancellationToken token = CancellationScope.current();
        // 请求还没发出就已经被取消：直接短路，别浪费一次 API 调用。
        if (token != null && token.isCancelled()) {
            throw new IOException(CANCELLED_MESSAGE);
        }

        Call call = httpClient().newCall(request.build());
        Runnable hook = token == null ? null : token.onCancel(call::cancel);
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBody != null ? responseBody.string() : "无响应体";
                throw new IOException("API 请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBody == null) {
                throw new IOException("API 返回空响应体");
            }
            return parseStream(responseBody.source(), streamListener, token);
        } catch (IOException e) {
            // Call.cancel() 会让阻塞中的 read 抛 IOException("Canceled")，
            // 这里统一翻译成取消语义，避免上层把主动取消误报成网络故障。
            if (token != null && token.isCancelled()) {
                throw new IOException(CANCELLED_MESSAGE);
            }
            throw e;
        } finally {
            if (hook != null) {
                token.removeHook(hook);
            }
        }
    }

    private ChatResponse parseStream(BufferedSource source, StreamListener listener, CancellationToken token)
            throws IOException {
        String role = "assistant";
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedInputTokens = 0;
        String lastContentDelta = "";

        while (!source.exhausted()) {
            // 每个 chunk 轮询一次：即使 Call.cancel() 因竞态没能打断本次 read，
            // 下一轮也一定会退出，不会把已取消的任务继续跑到底。
            if (token != null && token.isCancelled()) {
                throw new IOException(CANCELLED_MESSAGE);
            }
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

            inspectStreamChunk(root);

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

    protected ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("model", getModel());
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());

            // 多模态：user 消息可能携带文本 + 图片部件，序列化为 content 数组；
            // 其余消息（system/assistant/tool）一律纯文本字符串。
            List<LlmClient.ContentPart> parts = msg.parts();
            if (parts != null && !parts.isEmpty()) {
                ArrayNode contentParts = msgNode.putArray("content");
                for (LlmClient.ContentPart part : parts) {
                    ObjectNode partNode = contentParts.addObject();
                    if (part.isImage()) {
                        partNode.put("type", "image_url");
                        ObjectNode imageUrlNode = partNode.putObject("image_url");
                        imageUrlNode.put("url", part.imageUrl() == null ? "" : part.imageUrl());
                    } else {
                        partNode.put("type", "text");
                        partNode.put("text", part.text() == null ? "" : part.text());
                    }
                }
            } else {
                msgNode.put("content", msg.content() == null ? "" : msg.content());
            }

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

    /**
     * 提取一段 reasoning 增量。
     *
     * <p>兼容三种厂商方言：{@code reasoning_content}（DeepSeek / Kimi / Step 系）、
     * {@code reasoning}（部分聚合网关）、{@code reasoning_details} 数组（OpenRouter 风格，
     * 逐项取 {@code text}，缺失时回退 {@code content}，按序拼接）。
     */
    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (!details.isArray() || details.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (JsonNode detail : details) {
            String text = detail.path("text").asText("");
            if (text.isEmpty()) {
                text = detail.path("content").asText("");
            }
            merged.append(text);
        }
        return merged.toString();
    }

    /**
     * 解析缓存命中的输入 token 数。
     *
     * <p>各厂商字段名不统一，按「后写覆盖先写」的顺序依次尝试：
     * {@code cached_tokens} / {@code prompt_cache_hit_tokens} / {@code prompt_tokens_details.cached_tokens}
     * / {@code input_cache_hit_tokens} / {@code input_tokens_details.cached_tokens}。
     */
    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
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
