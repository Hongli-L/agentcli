package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.config.LspConfigLoader;
import com.agentcli.lsp.config.LspServerConfig;
import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;
import com.agentcli.lsp.transport.LspStdioTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * LSP 管理门面。负责按语言启动/停止语言服务器、执行诊断，并在
 * 进程崩溃 / 未启动 / 缺少能力时优雅降级。
 */
public class LspServerManager {

    private final LspConfig config;
    private final Map<String, LspServer> servers = new ConcurrentHashMap<>();
    private final Map<String, LspStdioTransport> transports = new ConcurrentHashMap<>();
    private final Map<String, DiagnosticsStore> stores = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public LspServerManager(LspConfig config) {
        this.config = config;
    }

    public LspConfig config() {
        return config;
    }

    // ---- 启动 / 停止 ----

    /**
     * 启动某语言服务器（使用当前配置中的 server 定义）。
     *
     * @param languageId 语言标识
     */
    public void start(String languageId) {
        start(languageId, null);
    }

    /**
     * 启动某语言服务器，可额外指定一个配置文件（覆盖该语言的 server 定义）。
     *
     * @param languageId        语言标识
     * @param overrideConfigPath 可选的配置文件路径（可为 null）
     */
    public void start(String languageId, String overrideConfigPath) {
        if (overrideConfigPath != null && !overrideConfigPath.isBlank()) {
            try {
                LspServerConfig sc = LspConfigLoader.loadServerConfig(
                        Path.of(overrideConfigPath), languageId, config.projectDir());
                if (sc != null) {
                    config.setServer(languageId, sc);
                }
            } catch (Exception ignored) {
                // 配置加载失败则沿用现有配置
            }
        }
        startInternal(languageId);
    }

    private void startInternal(String languageId) {
        LspServer existing = servers.get(languageId);
        if (existing != null && existing.status() == LspServer.Status.READY) {
            return;
        }
        if (existing != null) {
            stop(languageId);
        }

        LspServerConfig cfg = config.server(languageId);
        LspServer server = new LspServer();
        server.setLanguageId(languageId);
        servers.put(languageId, server);

        if (cfg == null) {
            server.setStatus(LspServer.Status.ERROR);
            server.setLastError("未配置语言服务器：" + languageId);
            return;
        }

        try {
            File root = cfg.rootPath() != null ? new File(cfg.rootPath()) : new File(config.projectDir());
            LspStdioTransport transport = new LspStdioTransport(
                    cfg.command(), cfg.args(), cfg.env(), root);
            transports.put(languageId, transport);
            DiagnosticsStore store = new DiagnosticsStore();
            stores.put(languageId, store);

            LspClient client = new LspClient(transport, store);
            server.setClient(client);

            // 崩溃降级：进程退出时标记 ERROR 并停止注入
            final String lang = languageId;
            transport.process().onExit().thenRun(() -> onServerExit(lang));

            // 构建 initialize 参数
            ObjectNode caps = mapper.createObjectNode();
            ObjectNode textDoc = caps.putObject("textDocument");
            textDoc.putObject("publishDiagnostics");
            textDoc.putObject("synchronization");
            ObjectNode clientInfo = mapper.createObjectNode();
            clientInfo.put("name", "agentcli");
            clientInfo.put("version", "1.0.0");

            String rootUri = toUri(root.getAbsolutePath());
            client.initialize(rootUri, caps, clientInfo, cfg.initializationOptions());

            // 能力检测：是否支持主动下发诊断
            boolean publish = client.supportsDiagnostics();
            server.setPublishSupported(publish);

            server.setStatus(LspServer.Status.READY);
        } catch (TimeoutException e) {
            server.setStatus(LspServer.Status.ERROR);
            server.setLastError("启动超时：" + e.getMessage());
            safeClose(languageId);
        } catch (Exception e) {
            server.setStatus(LspServer.Status.ERROR);
            server.setLastError("启动失败：" + e.getMessage());
            safeClose(languageId);
        }
    }

    private boolean isPublishSupported(JsonNode caps) {
        if (caps == null || !caps.isObject()) {
            return false;
        }
        JsonNode td = caps.get("textDocument");
        if (td == null || !td.isObject()) {
            return false;
        }
        return td.get("publishDiagnostics") != null;
    }

    private void onServerExit(String languageId) {
        LspServer s = servers.get(languageId);
        if (s == null) {
            return;
        }
        if (s.status() == LspServer.Status.STOPPED) {
            return; // 正常关闭，忽略
        }
        s.setStatus(LspServer.Status.ERROR);
        s.setLastError("语言服务器进程已退出（崩溃）");
        LspStdioTransport t = transports.get(languageId);
        if (t != null) {
            for (String line : t.stderrLines()) {
                s.addStderrLine(line);
            }
        }
    }

    /**
     * 停止某语言服务器（shutdown + exit + close）。
     *
     * @param languageId 语言标识
     */
    public void stop(String languageId) {
        LspServer server = servers.get(languageId);
        if (server == null) {
            return;
        }
        LspClient client = server.client();
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception ignored) {
                // 进程可能已死
            }
            try {
                client.exit();
            } catch (Exception ignored) {
                // ignore
            }
        }
        server.setStatus(LspServer.Status.STOPPED);
        safeClose(languageId);
    }

    /** 停止全部语言服务器。 */
    public void stopAll() {
        for (String lang : new ArrayList<>(servers.keySet())) {
            stop(lang);
        }
    }

    private void safeClose(String languageId) {
        LspStdioTransport t = transports.remove(languageId);
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        DiagnosticsStore store = stores.get(languageId);
        if (store != null) {
            store.clear();
        }
    }

    // ---- 查询 ----

    /** @return 语言服务器状态（未启动时返回 STOPPED） */
    public LspServer.Status status(String languageId) {
        LspServer s = servers.get(languageId);
        if (s == null) {
            return LspServer.Status.STOPPED;
        }
        // 刷新 stderr 尾部
        LspStdioTransport t = transports.get(languageId);
        if (t != null) {
            for (String line : t.stderrLines()) {
                if (!s.stderrTail().contains(line)) {
                    s.addStderrLine(line);
                }
            }
        }
        return s.status();
    }

    /** @return 语言服务器运行时（用于状态展示），可能为 null */
    public LspServer server(String languageId) {
        return servers.get(languageId);
    }

    /**
     * 该语言是否存在可用服务器（已配置且当前 READY）。
     *
     * @param languageId 语言标识
     * @return 是否可用
     */
    public boolean isAvailable(String languageId) {
        if (!config.hasServer(languageId)) {
            return false;
        }
        LspServer s = servers.get(languageId);
        return s != null && s.status() == LspServer.Status.READY;
    }

    // ---- 诊断 ----

    /**
     * 对指定文件执行诊断（didOpen/didChange → 等待 publishDiagnostics）。
     * 崩溃 / 未启动 / 缺少能力均优雅降级，超时返回当前快照，绝不向外抛出异常。
     *
     * @param languageId 语言标识
     * @param uri        文档 URI（file:///...）
     * @param text       文档当前内容
     * @param timeoutMs  等待诊断的超时毫秒
     * @return 该文档的 error + warning 列表（降级时为空）
     */
    public List<Diagnostic> diagnoseFile(String languageId, String uri, String text, long timeoutMs) {
        List<Diagnostic> all = runDiagnosis(languageId, uri, text, timeoutMs);
        return all.stream()
                .filter(d -> d.severity() == DiagnosticSeverity.ERROR
                        || d.severity() == DiagnosticSeverity.WARNING)
                .collect(Collectors.toList());
    }

    /**
     * 同 {@link #diagnoseFile} 但返回全部级别（含 info / hint），用于 {@code /lsp diagnostics} 展示。
     */
    public List<Diagnostic> diagnoseFileFull(String languageId, String uri, String text, long timeoutMs) {
        return runDiagnosis(languageId, uri, text, timeoutMs);
    }

    /**
     * 诊断核心实现，返回全部级别诊断。任何异常均被吸收，返回空列表。
     */
    private List<Diagnostic> runDiagnosis(String languageId, String uri, String text, long timeoutMs) {
        LspServer server = servers.get(languageId);
        LspStdioTransport transport = transports.get(languageId);
        DiagnosticsStore store = stores.get(languageId);

        if (server == null || transport == null || store == null) {
            return Collections.emptyList();
        }
        if (server.status() != LspServer.Status.READY) {
            return Collections.emptyList();
        }
        if (!transport.isAlive()) {
            server.setStatus(LspServer.Status.ERROR);
            server.setLastError("语言服务器进程已退出");
            return Collections.emptyList();
        }
        if (!server.isPublishSupported()) {
            // 能力不支持：直接降级为空，不阻塞
            return Collections.emptyList();
        }

        try {
            store.registerAwait(uri);
            LspClient client = server.client();
            if (server.isOpened(uri)) {
                client.didChange(uri, languageId, text == null ? "" : text);
            } else {
                client.didOpen(uri, languageId, text == null ? "" : text);
                server.markOpened(uri);
            }

            boolean received = store.await(uri, timeoutMs);
            if (!received) {
                if (!transport.isAlive()) {
                    server.setStatus(LspServer.Status.ERROR);
                    server.setLastError("等待诊断超时且进程已退出");
                }
                // 降级：返回当前快照（可能为空）
            }
            return store.get(uri);
        } catch (Exception e) {
            server.setLastError("诊断失败：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取某 URI 的全部已知诊断（跨语言扫描，全级别）。
     *
     * @param uri 文档 URI
     * @return 诊断列表
     */
    public List<Diagnostic> getDiagnostics(String uri) {
        List<Diagnostic> result = new ArrayList<>();
        for (DiagnosticsStore store : stores.values()) {
            result.addAll(store.get(uri));
        }
        return result;
    }

    /**
     * 获取全部已知诊断（全级别）。
     *
     * @return 诊断列表
     */
    public List<Diagnostic> getAllDiagnostics() {
        List<Diagnostic> result = new ArrayList<>();
        for (DiagnosticsStore store : stores.values()) {
            result.addAll(store.getAll());
        }
        return result;
    }

    private static String toUri(String absolutePath) {
        String p = absolutePath.replace('\\', '/');
        if (p.startsWith("/")) {
            return "file://" + p;
        }
        return "file:///" + p;
    }
}
