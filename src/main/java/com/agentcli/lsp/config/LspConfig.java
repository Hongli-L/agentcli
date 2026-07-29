package com.agentcli.lsp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LSP 全局配置：各语言服务器配置 + 反馈设置。
 *
 * <p>内置默认 java 模板（指向 {@code ${JDT_LS_HOME}} 与 {@code ${PROJECT_DIR}}），
 * 用户设置环境变量 {@code JDT_LS_HOME} 后即可通过 {@code /lsp start} 开箱可用。
 */
public class LspConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Map<String, LspServerConfig> servers = new LinkedHashMap<>();
    private boolean autoFeedback = true;
    private String feedbackScope = "changed";
    private String projectDir = ".";

    public LspConfig() {
        addDefaultJava();
    }

    /** @return 各语言服务器配置（不可变视图） */
    public Map<String, LspServerConfig> servers() {
        return servers;
    }

    public LspServerConfig server(String languageId) {
        return servers.get(languageId);
    }

    public void setServer(String languageId, LspServerConfig cfg) {
        servers.put(languageId, cfg);
    }

    public boolean hasServer(String languageId) {
        return servers.containsKey(languageId);
    }

    public boolean isAutoFeedback() {
        return autoFeedback;
    }

    public void setAutoFeedback(boolean autoFeedback) {
        this.autoFeedback = autoFeedback;
    }

    public String feedbackScope() {
        return feedbackScope;
    }

    public void setFeedbackScope(String feedbackScope) {
        this.feedbackScope = (feedbackScope == null || feedbackScope.isBlank()) ? "changed" : feedbackScope;
    }

    public String projectDir() {
        return projectDir;
    }

    public void setProjectDir(String projectDir) {
        this.projectDir = (projectDir == null || projectDir.isBlank()) ? "." : projectDir;
    }

    /** 添加内置默认 java 服务器模板（已配置时跳过）。 */
    public void addDefaultJava() {
        if (servers.containsKey("java")) {
            return;
        }
        LspServerConfig java = new LspServerConfig();
        java.setLanguageId("java");
        java.setCommand("java");
        java.setArgs(javaDefaultArgs());
        java.setRootPath("${PROJECT_DIR}");
        ObjectNode init = MAPPER.createObjectNode();
        init.putObject("settings");
        java.setInitializationOptions(init);
        servers.put("java", java);
    }

    private java.util.List<String> javaDefaultArgs() {
        return java.util.List.of(
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.level=ALL",
                "-jar",
                "${JDT_LS_HOME}/plugins/org.eclipse.equinox.launcher_1.6.0.v20230501-0816.jar",
                "-configuration",
                "${JDT_LS_HOME}/config_win",
                "-data",
                "${PROJECT_DIR}/.agentcli/lsp-workspace");
    }

    public ObjectNode toJson() {
        ObjectNode n = MAPPER.createObjectNode();
        ObjectNode serversNode = n.putObject("servers");
        for (Map.Entry<String, LspServerConfig> e : servers.entrySet()) {
            serversNode.set(e.getKey(), e.getValue().toJson());
        }
        n.put("autoFeedback", autoFeedback);
        n.put("feedbackScope", feedbackScope);
        return n;
    }

    public static LspConfig fromJson(JsonNode node) {
        LspConfig cfg = new LspConfig();
        cfg.servers.clear();
        if (node == null || !node.isObject()) {
            return cfg;
        }
        if (node.has("servers") && node.get("servers").isObject()) {
            node.get("servers").fields().forEachRemaining(f -> {
                LspServerConfig sc = LspServerConfig.fromJson(f.getValue());
                if (sc.languageId() == null) {
                    sc.setLanguageId(f.getKey());
                }
                cfg.servers.put(f.getKey(), sc);
            });
        }
        if (node.has("autoFeedback")) {
            cfg.autoFeedback = node.get("autoFeedback").asBoolean(true);
        }
        if (node.has("feedbackScope")) {
            cfg.feedbackScope = node.get("feedbackScope").asText("changed");
        }
        return cfg;
    }

    /** 合并另一个配置（后者覆盖前者同名键）。 */
    public void mergeFrom(LspConfig other) {
        if (other == null) {
            return;
        }
        for (Map.Entry<String, LspServerConfig> e : other.servers.entrySet()) {
            servers.put(e.getKey(), e.getValue());
        }
        if (other.autoFeedback != this.autoFeedback) {
            this.autoFeedback = other.autoFeedback;
        }
        if (other.feedbackScope != null) {
            this.feedbackScope = other.feedbackScope;
        }
    }
}
