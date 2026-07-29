package com.agentcli.lsp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个语言服务器的启动配置。
 *
 * @param languageId            语言标识（如 "java"）
 * @param command              可执行命令
 * @param args                 启动参数
 * @param env                  额外环境变量
 * @param rootPath             项目根路径（可为 null，由管理器补全为 ${PROJECT_DIR}）
 * @param initializationOptions 初始化选项（JsonNode，可为 null）
 */
public class LspServerConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String languageId;
    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new LinkedHashMap<>();
    private String rootPath;
    private JsonNode initializationOptions;

    public LspServerConfig() {
    }

    public LspServerConfig(String languageId, String command, List<String> args) {
        this.languageId = languageId;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
    }

    // ---- getters / setters ----

    public String languageId() {
        return languageId;
    }

    public void setLanguageId(String languageId) {
        this.languageId = languageId;
    }

    public String command() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> args() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
    }

    public Map<String, String> env() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env != null ? new LinkedHashMap<>(env) : new LinkedHashMap<>();
    }

    public String rootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public JsonNode initializationOptions() {
        return initializationOptions;
    }

    public void setInitializationOptions(JsonNode initializationOptions) {
        this.initializationOptions = initializationOptions;
    }

    // ---- JSON 序列化 ----

    public ObjectNode toJson() {
        ObjectNode n = MAPPER.createObjectNode();
        if (languageId != null) {
            n.put("languageId", languageId);
        }
        n.put("command", command == null ? "" : command);
        ArrayNode a = n.putArray("args");
        for (String arg : args) {
            a.add(arg);
        }
        ObjectNode e = n.putObject("env");
        for (Map.Entry<String, String> entry : env.entrySet()) {
            e.put(entry.getKey(), entry.getValue());
        }
        if (rootPath != null) {
            n.put("rootPath", rootPath);
        }
        if (initializationOptions != null) {
            n.set("initializationOptions", initializationOptions);
        }
        return n;
    }

    public static LspServerConfig fromJson(JsonNode node) {
        LspServerConfig cfg = new LspServerConfig();
        if (node == null || !node.isObject()) {
            return cfg;
        }
        if (node.has("languageId")) {
            cfg.languageId = node.get("languageId").asText();
        }
        if (node.has("command")) {
            cfg.command = node.get("command").asText();
        }
        if (node.has("args") && node.get("args").isArray()) {
            for (JsonNode a : node.get("args")) {
                cfg.args.add(a.asText());
            }
        }
        if (node.has("env") && node.get("env").isObject()) {
            node.get("env").fields().forEachRemaining(f -> cfg.env.put(f.getKey(), f.getValue().asText()));
        }
        if (node.has("rootPath")) {
            cfg.rootPath = node.get("rootPath").asText();
        }
        if (node.has("initializationOptions")) {
            cfg.initializationOptions = node.get("initializationOptions");
        }
        return cfg;
    }
}
