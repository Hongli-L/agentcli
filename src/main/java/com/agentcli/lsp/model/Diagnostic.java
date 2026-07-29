package com.agentcli.lsp.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LSP 单条诊断。
 *
 * @param uri       文档 URI（file:///...）
 * @param severity  级别
 * @param range     范围
 * @param code      诊断码（可为空字符串）
 * @param source    来源（如编译器名，可为空字符串）
 * @param message   诊断信息
 */
public record Diagnostic(String uri, DiagnosticSeverity severity, Range range, String code, String source, String message) {

    /**
     * 由 LSP publishDiagnostics 中的单条诊断节点构造。
     *
     * @param defaultUri 该批诊断所属的文档 URI
     * @param node       诊断 JSON 节点
     * @return 诊断对象；解析失败时返回 null
     */
    public static Diagnostic fromJson(String defaultUri, JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String uri = node.has("uri") && !node.get("uri").isNull()
                ? node.get("uri").asText() : defaultUri;
        int sevCode = node.has("severity") && !node.get("severity").isNull()
                ? node.get("severity").asInt() : DiagnosticSeverity.ERROR.code();
        DiagnosticSeverity severity = DiagnosticSeverity.fromCode(sevCode);

        Range range = parseRange(node.get("range"));
        String code = node.has("code") && !node.get("code").isNull() ? node.get("code").asText() : "";
        String source = node.has("source") && !node.get("source").isNull() ? node.get("source").asText() : "";
        String message = node.has("message") && !node.get("message").isNull() ? node.get("message").asText() : "";
        return new Diagnostic(uri, severity, range, code, source, message);
    }

    private static Range parseRange(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        Position start = parsePosition(node.get("start"));
        Position end = parsePosition(node.get("end"));
        return new Range(start, end);
    }

    private static Position parsePosition(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Position(0, 0);
        }
        int line = node.has("line") && !node.get("line").isNull() ? node.get("line").asInt() : 0;
        int character = node.has("character") && !node.get("character").isNull() ? node.get("character").asInt() : 0;
        return new Position(line, character);
    }

    /** @return 是否至少为 warning（error 或 warning），用于注入过滤 */
    public boolean isErrorOrWarning() {
        return severity == DiagnosticSeverity.ERROR || severity == DiagnosticSeverity.WARNING;
    }
}
