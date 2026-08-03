package com.agentcli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 {@code /embeddings} 端点的唯一实现。
 *
 * <p>OpenAI、Ollama（{@code /v1} 兼容层）、各类聚合网关共用同一套请求/响应格式，
 * 因此不需要为每家厂商写一个客户端——换 provider 只是改 {@code baseUrl} 与 {@code model} 两个配置。
 *
 * <p>与 {@code OpenAiCompatibleClient} 同栈复用 OkHttp 与 Jackson，共享静态 {@link OkHttpClient}
 * 单例以复用连接池：{@code /index} 会连续发出成百上千次请求，每次新建客户端会把 TCP 握手成本放大到不可接受。
 */
public final class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    /** 单条输入的字符上限，与 {@link CodeChunker#MAX_CHUNK_CHARS} 对齐；正常路径不会触发。 */
    static final int MAX_INPUT_CHARS = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    /** 回环地址集合：这些 host 上的服务（典型是 Ollama）通常不需要 API Key。 */
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");

    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds("agentcli.embedding.connect.timeout.seconds", 30), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds("agentcli.embedding.read.timeout.seconds", 120), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds("agentcli.embedding.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds("agentcli.embedding.call.timeout.seconds", 180), TimeUnit.SECONDS)
            .build();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient http;

    public OpenAiCompatibleEmbeddingClient(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, SHARED_HTTP_CLIENT);
    }

    /** 供测试注入自定义 OkHttp 客户端（例如缩短超时）。 */
    OpenAiCompatibleEmbeddingClient(String apiKey, String baseUrl, String model, OkHttpClient http) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.http = http == null ? SHARED_HTTP_CLIENT : http;
    }

    @Override
    public float[] embed(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        String input = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("input", input);

        String response = postJson(resolveEmbeddingsUrl(baseUrl), body.toString());
        return parseEmbedding(response);
    }

    /**
     * 配置完备性判定（唯一权威实现，测试以此为准）：
     * <pre>
     * baseUrl 为空                      -> false
     * model 为空                        -> false
     * apiKey 非空                       -> true
     * apiKey 为空 且 baseUrl 指向回环地址 -> true   // Ollama 本地无 key 场景
     * apiKey 为空 且 baseUrl 指向远程     -> false
     * </pre>
     */
    @Override
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        if (baseUrl.isEmpty()) {
            return "未配置 embedding.baseUrl";
        }
        if (model.isEmpty()) {
            return "未配置 embedding.model";
        }
        if (!apiKey.isEmpty()) {
            return null;
        }
        if (isLoopback(baseUrl)) {
            return null;
        }
        return "未配置 embedding.apiKey";
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    private String postJson(String url, String body) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new IOException("Embedding 请求失败 [" + response.code() + "]: " + brief(text));
            }
            return text;
        } catch (IllegalArgumentException e) {
            // OkHttp 对畸形 URL 抛的是运行时异常，统一收敛成 IOException 让上层按降级处理
            throw new IOException("Embedding 端点地址无效: " + url, e);
        }
    }

    private float[] parseEmbedding(String responseBody) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new IOException("Embedding 响应不是合法 JSON: " + brief(responseBody), e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("Embedding 响应缺少 data 字段: " + brief(responseBody));
        }
        JsonNode vector = data.get(0).path("embedding");
        if (!vector.isArray() || vector.isEmpty()) {
            throw new IOException("Embedding 响应缺少 embedding 数组: " + brief(responseBody));
        }
        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            result[i] = (float) vector.get(i).asDouble();
        }
        return result;
    }

    /** 规范化 embeddings 端点：去尾部斜杠；已以 {@code /embeddings} 结尾则原样返回。 */
    static String resolveEmbeddingsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/embeddings")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/embeddings";
    }

    /** 判断端点是否指向本机回环地址；解析失败时保守地按远程处理。 */
    static boolean isLoopback(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(baseUrl.trim());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** 错误信息里的响应体截断，避免把几十 KB 的 HTML 错误页糊进日志与提示。 */
    private static String brief(String text) {
        if (text == null || text.isEmpty()) {
            return "(空响应体)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
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
}
