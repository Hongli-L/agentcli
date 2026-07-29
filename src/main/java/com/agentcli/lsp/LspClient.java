package com.agentcli.lsp;

import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.transport.LspStdioTransport;
import com.agentcli.mcp.jsonrpc.JsonRpcClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * LSP 客户端。组合复用的 {@link JsonRpcClient} 与 {@link LspStdioTransport}，
 * 并持有 {@link DiagnosticsStore} 引用：
 * <ul>
 *     <li>initialize / initialized / didOpen / didChange / shutdown / exit；</li>
 *     <li>对端下发的 {@code textDocument/publishDiagnostics} 由
 *     {@link JsonRpcClient#onNotification} 兜底——解析后写入 store 并完成 await。</li>
 * </ul>
 *
 * <p>本类仅依赖 {@code JsonRpcClient}（重写自复用），节点构造使用本地 {@link ObjectMapper}，
 * 不复用 {@code JsonRpcClient} 未提供的 {@code newObject}/{@code newArray} 助手。
 */
public class LspClient {

    /** initialize 请求超时（秒）。 */
    private static final long INITIALIZE_TIMEOUT_SECONDS = 60L;

    /** shutdown 请求超时（秒）。 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcClient rpc;
    private final DiagnosticsStore store;
    private JsonNode serverCapabilities = null;

    public LspClient(LspStdioTransport transport, DiagnosticsStore store) {
        this.store = store;
        this.rpc = new JsonRpcClient(transport);
        // 注册通知回调：publishDiagnostics 写入 store
        this.rpc.onNotification(this::handleNotification);
    }

    private void handleNotification(JsonNode notification) {
        if (notification == null || !notification.isObject()) {
            return;
        }
        String method = notification.has("method") ? notification.get("method").asText() : "";
        if (!"textDocument/publishDiagnostics".equals(method)) {
            return;
        }
        JsonNode params = notification.get("params");
        if (params == null || !params.isObject()) {
            return;
        }
        String uri = params.has("uri") ? params.get("uri").asText() : "";
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (params.has("diagnostics") && params.get("diagnostics").isArray()) {
            for (JsonNode d : params.get("diagnostics")) {
                Diagnostic diag = Diagnostic.fromJson(uri, d);
                if (diag != null) {
                    diagnostics.add(diag);
                }
            }
        }
        store.update(uri, diagnostics);
    }

    /**
     * 执行 initialize 握手：发送 initialize 请求、保存 serverCapabilities、发送 initialized 通知。
     *
     * @param rootUri              项目根 URI
     * @param capabilities         客户端能力节点
     * @param clientInfo          客户端信息节点
     * @param initializationOptions 初始化选项节点（可为 null）
     * @throws IOException     发送失败
     * @throws TimeoutException 握手超时
     */
    public void initialize(String rootUri, JsonNode capabilities, JsonNode clientInfo, JsonNode initializationOptions)
            throws IOException, TimeoutException {
        ObjectNode params = MAPPER.createObjectNode();
        try {
            params.put("processId", ProcessHandle.current().pid());
        } catch (Exception ignored) {
            params.putNull("processId");
        }
        params.put("rootUri", rootUri == null ? "" : rootUri);
        params.putNull("rootPath");
        params.set("capabilities", capabilities != null ? capabilities : MAPPER.createObjectNode());
        params.set("clientInfo", clientInfo != null ? clientInfo : defaultClientInfo());
        if (initializationOptions != null) {
            params.set("initializationOptions", initializationOptions);
        }

        JsonNode result = rpc.request("initialize", params, INITIALIZE_TIMEOUT_SECONDS);
        if (result != null && result.has("capabilities")) {
            this.serverCapabilities = result.get("capabilities");
        } else {
            this.serverCapabilities = MAPPER.createObjectNode();
        }
        rpc.sendNotification("initialized", MAPPER.createObjectNode());
    }

    private ObjectNode defaultClientInfo() {
        ObjectNode ci = MAPPER.createObjectNode();
        ci.put("name", "agentcli");
        ci.put("version", "1.0.0");
        return ci;
    }

    /**
     * 通知对端打开文档。
     */
    public void didOpen(String uri, String languageId, String text) throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("uri", uri);
        doc.put("languageId", languageId == null ? "" : languageId);
        doc.put("version", 1);
        doc.put("text", text == null ? "" : text);
        params.set("textDocument", doc);
        rpc.sendNotification("textDocument/didOpen", params);
    }

    /**
     * 通知对端文档内容变更。
     */
    public void didChange(String uri, String languageId, String text) throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("uri", uri);
        doc.put("languageId", languageId == null ? "" : languageId);
        doc.put("version", System.nanoTime());
        ObjectNode content = MAPPER.createObjectNode();
        content.put("text", text == null ? "" : text);
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        arr.add(content);
        doc.set("contentChanges", arr);
        params.set("textDocument", doc);
        rpc.sendNotification("textDocument/didChange", params);
    }

    /**
     * 发送 shutdown 请求。
     */
    public void shutdown() throws IOException, TimeoutException {
        rpc.request("shutdown", MAPPER.createObjectNode(), SHUTDOWN_TIMEOUT_SECONDS);
    }

    /**
     * 发送 exit 通知。
     */
    public void exit() throws IOException {
        rpc.sendNotification("exit", MAPPER.createObjectNode());
    }

    /** @return 服务端能力节点（initialize 后有效） */
    public JsonNode serverCapabilities() {
        return serverCapabilities;
    }

    /** @return 底层 JSON-RPC 客户端（测试用） */
    public JsonRpcClient rpcClient() {
        return rpc;
    }

    /**
     * 是否支持主动下发诊断（依据 serverCapabilities 的
     * {@code textDocument.publishDiagnostics} 能力位判断）。
     */
    public boolean supportsDiagnostics() {
        return isPublishSupported(serverCapabilities);
    }

    private static boolean isPublishSupported(JsonNode caps) {
        if (caps == null || !caps.isObject()) {
            return false;
        }
        JsonNode td = caps.get("textDocument");
        if (td == null || !td.isObject()) {
            return false;
        }
        return td.get("publishDiagnostics") != null;
    }

    /** 暴露 ObjectMapper 构造能力节点（供测试组装参数）。 */
    public ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }
}
