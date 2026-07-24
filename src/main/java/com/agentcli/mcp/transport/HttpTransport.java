package com.agentcli.mcp.transport;

import com.agentcli.mcp.protocol.McpInitializeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MCP Streamable HTTP transport (StreamableHTTP).
 *
 * Implements the {@link McpTransport} contract over HTTP using OkHttp. Each JSON-RPC
 * request is POSTed to the server endpoint; the server may answer with a plain JSON body
 * or with a streamed {@code text/event-stream} body containing one or more SSE messages
 * (e.g. notifications). The session is tracked via the {@code Mcp-Session-Id} response
 * header and replayed on subsequent requests. Closing the transport issues a best-effort
 * HTTP DELETE to tear the session down.
 */
public class HttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String PROTOCOL_VERSION = McpInitializeRequest.PROTOCOL_VERSION;

    private final OkHttpClient client;
    private final String url;
    private final Map<String, String> headers;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile String sessionId;

    public HttpTransport(String url, Map<String, String> headers) {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public void send(JsonNode message) throws IOException {
        String body = MAPPER.writeValueAsString(message);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.forEach(builder::header);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            String newSession = response.header("Mcp-Session-Id");
            if (newSession != null && !newSession.isBlank()) {
                sessionId = newSession;
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            String raw = response.body() == null ? null : response.body().string();
            if (raw == null || raw.isBlank()) {
                return;
            }
            String contentType = response.header("Content-Type", "");
            List<JsonNode> nodes = contentType.contains("text/event-stream")
                    ? parseSse(raw)
                    : List.of(MAPPER.readTree(raw));
            for (JsonNode node : nodes) {
                for (Consumer<JsonNode> listener : listeners) {
                    listener.accept(node);
                }
            }
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        OkHttpClient shortClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .build();
        try (Response ignored = shortClient.newCall(request).execute()) {
            // best-effort session teardown
        } catch (IOException ignored) {
            // ignore: session cleanup is opportunistic
        }
    }

    private static List<JsonNode> parseSse(String raw) {
        List<JsonNode> result = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            if (line.isBlank()) {
                if (data.length() > 0) {
                    try {
                        result.add(MAPPER.readTree(data.toString()));
                    } catch (Exception ignored) {
                    }
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String content = line.substring("data:".length()).strip();
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(content);
            }
        }
        if (data.length() > 0) {
            try {
                result.add(MAPPER.readTree(data.toString()));
            } catch (Exception ignored) {
            }
        }
        return result;
    }
}
