package com.agentcli.lsp.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 用于测试的伪语言服务器。作为独立子进程被拉起，严格遵循双向
 * {@code Content-Length} 帧协议。
 *
 * <p>支持的启动参数：
 * <ul>
 *     <li>{@code --diagnostics <json>}：诊断规则数组
 *         {@code [{"contains":"子串","diagnostics":[模板...]}, ...]}，
 *         匹配第一个包含该子串的文本；可用 {@code "contains":"*"} 作为兜底。</li>
 *     <li>{@code --no-diagnostics}：不在 capabilities 中声明 publishDiagnostics，且绝不下发诊断（测能力不支持）。</li>
 *     <li>{@code --hang}：声明能力但收到 didOpen/didChange 后不下发诊断（测超时）。</li>
 *     <li>{@code --crash-after <n>}：处理完第 n 条消息后退出（测崩溃降级）。</li>
 * </ul>
 */
public final class FakeLanguageServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<DiagRule> rules = new ArrayList<>();
    private boolean noDiagnostics = false;
    private boolean hang = false;
    private int crashAfter = Integer.MAX_VALUE;

    /** 诊断规则。 */
    private static final class DiagRule {
        final String contains;
        final List<ObjectNode> diagnostics;

        DiagRule(String contains, List<ObjectNode> diagnostics) {
            this.contains = contains;
            this.diagnostics = diagnostics;
        }
    }

    public static void main(String[] args) throws Exception {
        new FakeLanguageServer().run(args);
    }

    private void run(String[] args) throws Exception {
        parseArgs(args);

        BufferedInputStream in = new BufferedInputStream(System.in);
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        int msgCount = 0;
        while (true) {
            String json = readFrame(in);
            if (json == null) {
                break; // EOF
            }
            msgCount++;
            JsonNode msg;
            try {
                msg = MAPPER.readTree(json);
            } catch (Exception e) {
                continue;
            }
            String method = msg.has("method") ? msg.get("method").asText() : "";

            switch (method) {
                case "initialize" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    ObjectNode caps = MAPPER.createObjectNode();
                    ObjectNode textDoc = caps.putObject("textDocument");
                    if (!noDiagnostics) {
                        textDoc.putObject("publishDiagnostics");
                    }
                    textDoc.putObject("synchronization");
                    result.set("capabilities", caps);
                    ObjectNode info = MAPPER.createObjectNode();
                    info.put("name", "fake-lsp");
                    info.put("version", "0.1.0");
                    result.set("serverInfo", info);
                    sendResponse(out, msg.get("id").asInt(), result);
                }
                case "initialized" -> {
                    // 无需回应
                }
                case "textDocument/didOpen", "textDocument/didChange" -> {
                    String uri = msg.path("params").path("textDocument").path("uri").asText("");
                    String text = msg.path("params").path("textDocument").path("text").asText("");
                    if (!noDiagnostics && !hang) {
                        List<ObjectNode> matched = matchRules(text);
                        sendPublishDiagnostics(out, uri, matched);
                    }
                }
                case "shutdown" -> sendResponse(out, msg.get("id").asInt(), MAPPER.createObjectNode());
                case "exit" -> {
                    out.flush();
                    System.exit(0);
                }
                default -> {
                    // 未知方法：以 method-not-found 回应（若带 id）
                    if (msg.has("id")) {
                        ObjectNode err = MAPPER.createObjectNode();
                        err.put("code", -32601);
                        err.put("message", "Method not found: " + method);
                        sendResponse(out, msg.get("id").asInt(), null, err);
                    }
                }
            }

            if (msgCount >= crashAfter) {
                out.flush();
                System.exit(1);
            }
        }
        out.flush();
    }

    private List<ObjectNode> matchRules(String text) {
        if (text == null) {
            text = "";
        }
        List<ObjectNode> fallback = null;
        for (DiagRule rule : rules) {
            if ("*".equals(rule.contains)) {
                fallback = rule.diagnostics;
                continue;
            }
            if (text.contains(rule.contains)) {
                return rule.diagnostics;
            }
        }
        return fallback == null ? new ArrayList<>() : fallback;
    }

    private void sendPublishDiagnostics(BufferedWriter out, String uri, List<ObjectNode> templates)
            throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", uri);
        ArrayNode diags = params.putArray("diagnostics");
        for (ObjectNode tpl : templates) {
            ObjectNode d = MAPPER.createObjectNode();
            d.put("severity", tpl.path("severity").asInt(1));
            ObjectNode range = d.putObject("range");
            ObjectNode start = range.putObject("start");
            start.put("line", tpl.path("line").asInt(0));
            start.put("character", tpl.path("character").asInt(0));
            ObjectNode end = range.putObject("end");
            end.put("line", tpl.path("endLine").asInt(0));
            end.put("character", tpl.path("endCharacter").asInt(0));
            if (tpl.has("code")) {
                d.put("code", tpl.get("code").asText(""));
            }
            if (tpl.has("source")) {
                d.put("source", tpl.get("source").asText(""));
            }
            d.put("message", tpl.path("message").asText(""));
            diags.add(d);
        }
        ObjectNode notif = MAPPER.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.putNull("id");
        notif.put("method", "textDocument/publishDiagnostics");
        notif.set("params", params);
        sendFrame(out, MAPPER.writeValueAsString(notif));
    }

    private void sendResponse(BufferedWriter out, int id, JsonNode result) throws IOException {
        sendResponse(out, id, result, null);
    }

    private void sendResponse(BufferedWriter out, int id, JsonNode result, JsonNode error) throws IOException {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        if (error != null) {
            resp.set("error", error);
        } else {
            resp.set("result", result == null ? MAPPER.createObjectNode() : result);
        }
        sendFrame(out, MAPPER.writeValueAsString(resp));
    }

    private static String readHeaderLine(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] arr = bos.toByteArray();
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r') {
                    return new String(arr, 0, len - 1, StandardCharsets.UTF_8);
                }
                return new String(arr, StandardCharsets.UTF_8);
            }
            bos.write(c);
        }
        if (bos.size() == 0) {
            return null;
        }
        byte[] arr = bos.toByteArray();
        int len = arr.length;
        if (len > 0 && arr[len - 1] == '\r') {
            return new String(arr, 0, len - 1, StandardCharsets.UTF_8);
        }
        return new String(arr, StandardCharsets.UTF_8);
    }

    private static String readFrame(BufferedInputStream in) throws IOException {
        String headerLine = readHeaderLine(in);
        if (headerLine == null) {
            return null;
        }
        int contentLength = -1;
        if (headerLine.toLowerCase().startsWith("content-length:")) {
            contentLength = Integer.parseInt(headerLine.substring("content-length:".length()).trim());
        }
        while (true) {
            String h = readHeaderLine(in);
            if (h == null) {
                return null;
            }
            if (h.isEmpty()) {
                break;
            }
            if (h.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(h.substring("content-length:".length()).trim());
            }
        }
        if (contentLength < 0) {
            return "";
        }
        // Content-Length 为字节数，按字节精确读取（兼容 UTF-8 多字节）
        byte[] buf = new byte[contentLength];
        int total = 0;
        while (total < contentLength) {
            int r = in.read(buf, total, contentLength - total);
            if (r < 0) {
                break;
            }
            total += r;
        }
        return new String(buf, 0, total, StandardCharsets.UTF_8);
    }

    private static void sendFrame(BufferedWriter out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write("Content-Length: " + bytes.length + "\r\n\r\n");
        out.write(json);
        out.flush();
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--no-diagnostics".equals(a)) {
                noDiagnostics = true;
            } else if ("--hang".equals(a)) {
                hang = true;
            } else if ("--crash-after".equals(a) && i + 1 < args.length) {
                crashAfter = Integer.parseInt(args[++i]);
            } else if ("--diagnostics".equals(a) && i + 1 < args.length) {
                parseDiagnostics(args[++i]);
            } else if ("--diagnostics-file".equals(a) && i + 1 < args.length) {
                parseDiagnosticsFromFile(args[++i]);
            }
        }
    }

    private void parseDiagnosticsFromFile(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            parseDiagnostics(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 忽略读取失败
        }
    }

    private void parseDiagnostics(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isArray()) {
                for (JsonNode ruleNode : node) {
                    if (!ruleNode.isObject()) {
                        continue;
                    }
                    String contains = ruleNode.path("contains").asText("*");
                    List<ObjectNode> diags = new ArrayList<>();
                    if (ruleNode.has("diagnostics") && ruleNode.get("diagnostics").isArray()) {
                        for (JsonNode d : ruleNode.get("diagnostics")) {
                            if (d.isObject()) {
                                diags.add((ObjectNode) d);
                            }
                        }
                    }
                    rules.add(new DiagRule(contains, diags));
                }
            }
        } catch (Exception e) {
            // 忽略解析失败
        }
    }
}
