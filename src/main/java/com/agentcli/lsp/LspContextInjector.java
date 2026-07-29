package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.tool.ToolExecutionResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LSP 诊断注入器。在 ReAct 工具结果收集之后、写入对话历史之前被调用，
 * 对白名单工具产出的文件触发诊断，并将 error+warning 诊断块回灌到对应工具结果中。
 *
 * <p>特性：
 * <ul>
 *     <li>白名单识别：{@code {"write_file":"path"}}；</li>
 *     <li>autoFeedback 关闭或无可用服务器时零干预；</li>
 *     <li>同文件 300ms 内去抖合并；</li>
 *     <li>单轮（单次 afterToolResults）内单文件最多注入 1 次；</li>
 *     <li>幂等、线程安全、异常绝不向外抛出。</li>
 * </ul>
 */
public class LspContextInjector {

    /** 诊断等待超时（毫秒）。 */
    public static final long DIAGNOSE_TIMEOUT_MS = 5000L;

    /** 同文件去抖窗口（毫秒）。 */
    public static final long DEBOUNCE_MS = 300L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> TOOL_PATH_FIELD = new HashMap<>();

    static {
        TOOL_PATH_FIELD.put("write_file", "path");
    }

    private final LspServerManager manager;
    private final LspConfig config;
    private final LspLanguageDetector detector;
    private final LspDiagnosticsFormatter formatter;

    /** 每个 URI 上次诊断时刻（毫秒），用于去抖。 */
    private final Map<String, Long> lastDiagTime = new HashMap<>();

    public LspContextInjector(LspServerManager manager,
                              LspConfig config,
                              LspLanguageDetector detector,
                              LspDiagnosticsFormatter formatter) {
        this.manager = manager;
        this.config = config;
        this.detector = detector;
        this.formatter = formatter;
    }

    /**
     * 在工具结果收集后调用，按需注入 LSP 诊断。
     *
     * @param results 本轮工具结果
     * @return 可能已被注入诊断的结果列表（原列表不被修改）
     */
    public List<ToolExecutionResult> afterToolResults(List<ToolExecutionResult> results) {
        if (results == null) {
            return null;
        }
        if (results.isEmpty()) {
            return results;
        }
        if (manager == null || config == null || detector == null || formatter == null) {
            return results;
        }
        if (!config.isAutoFeedback()) {
            return results;
        }

        List<ToolExecutionResult> out = new ArrayList<>(results.size());
        Set<String> injectedThisRound = new HashSet<>();

        for (ToolExecutionResult r : results) {
            ToolExecutionResult toAdd = r;
            String field = TOOL_PATH_FIELD.get(r.name());
            if (field != null) {
                String path = safeArgument(r, field);
                if (path != null && !path.isBlank()) {
                    try {
                        Path file = Path.of(path);
                        String languageId = detector.languageIdForPath(file.toString());
                        if (languageId != null
                                && detector.hasConfiguredServer(languageId, config)
                                && manager.isAvailable(languageId)) {
                            String uri = toUri(file);
                            if (!injectedThisRound.contains(uri) && !isDebounced(uri)) {
                                List<Diagnostic> diags = manager.diagnoseFile(
                                        languageId, uri, readText(file), DIAGNOSE_TIMEOUT_MS);
                                markDiagnosed(uri);
                                if (diags != null && !diags.isEmpty()) {
                                    String block = formatter.formatInjection(uri, diags);
                                    String newResult = r.result()
                                            + System.lineSeparator()
                                            + block;
                                    toAdd = new ToolExecutionResult(
                                            r.id(), r.name(), r.argumentsJson(),
                                            newResult, r.elapsedMillis(), r.timedOut());
                                    injectedThisRound.add(uri);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 异常不向外抛出，保持零干预
                    }
                }
            }
            out.add(toAdd);
        }
        return out;
    }

    private boolean isDebounced(String uri) {
        Long last = lastDiagTime.get(uri);
        if (last == null) {
            return false;
        }
        return (System.currentTimeMillis() - last) < DEBOUNCE_MS;
    }

    private void markDiagnosed(String uri) {
        lastDiagTime.put(uri, System.currentTimeMillis());
    }

    private static String safeArgument(ToolExecutionResult r, String field) {
        try {
            String argsJson = r.argumentsJson();
            if (argsJson == null || argsJson.isBlank()) {
                return null;
            }
            JsonNode node = MAPPER.readTree(argsJson);
            if (node != null && node.isObject() && node.has(field)) {
                JsonNode v = node.get(field);
                return v.isValueNode() ? v.asText() : v.toString();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String toUri(Path file) {
        String abs = file.toAbsolutePath().toString().replace('\\', '/');
        if (abs.startsWith("/")) {
            return "file://" + abs;
        }
        return "file:///" + abs;
    }
}
