package com.agentcli.lsp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LSP 配置加载器。负责：
 * <ul>
 *     <li>读取 user（{@code ~/.agentcli/lsp.json}）与 project（{@code <projectDir>/.agentcli/lsp.json}）配置并合并；</li>
 *     <li>展开变量 {@code ${JDT_LS_HOME}} / {@code ${PROJECT_DIR}} 等；</li>
 *     <li>缺文件时使用内置默认配置；</li>
 *     <li>从任意配置文件单独加载某个语言的 server 定义（用于 {@code /lsp start --config}）。</li>
 * </ul>
 *
 * <p>变量展开逻辑镜像既有 {@code McpConfigLoader} 的做法。
 */
public class LspConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)\\}");

    private final String projectDir;

    public LspConfigLoader(String projectDir) {
        this.projectDir = (projectDir == null || projectDir.isBlank()) ? "." : projectDir;
    }

    /**
     * 计算变量映射。包含 JDT_LS_HOME / PROJECT_DIR / HOME / USER_HOME 以及全部系统环境变量。
     */
    private Map<String, String> computeVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        // 系统环境变量优先作为兜底
        vars.putAll(System.getenv());
        if (System.getenv("JDT_LS_HOME") != null) {
            vars.put("JDT_LS_HOME", System.getenv("JDT_LS_HOME"));
        }
        vars.put("PROJECT_DIR", projectDir);
        if (System.getProperty("user.home") != null) {
            vars.put("HOME", System.getProperty("user.home"));
            vars.put("USER_HOME", System.getProperty("user.home"));
        }
        return vars;
    }

    private static String expand(String value, Map<String, String> vars) {
        if (value == null) {
            return null;
        }
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String replacement = vars.get(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? m.group(0) : replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void expandConfig(LspConfig config, Map<String, String> vars) {
        for (LspServerConfig sc : config.servers().values()) {
            if (sc.command() != null) {
                sc.setCommand(expand(sc.command(), vars));
            }
            if (sc.args() != null) {
                sc.setArgs(sc.args().stream().map(a -> expand(a, vars)).toList());
            }
            if (sc.env() != null) {
                Map<String, String> newEnv = new LinkedHashMap<>();
                sc.env().forEach((k, v) -> newEnv.put(k, expand(v, vars)));
                sc.setEnv(newEnv);
            }
            if (sc.rootPath() != null) {
                sc.setRootPath(expand(sc.rootPath(), vars));
            }
            if (sc.initializationOptions() != null) {
                try {
                    String raw = MAPPER.writeValueAsString(sc.initializationOptions());
                    String expanded = expand(raw, vars);
                    JsonNode reparsed = MAPPER.readTree(expanded);
                    sc.setInitializationOptions(reparsed);
                } catch (Exception ignored) {
                    // 保留原值
                }
            }
        }
    }

    private LspConfig loadFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            String content = Files.readString(path);
            if (content.isBlank()) {
                return null;
            }
            return LspConfig.fromJson(MAPPER.readTree(content));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 加载并合并配置：内置默认 → user → project（后者覆盖前者），最后展开变量。
     *
     * @return 解析后的配置（永不为 null）
     */
    public LspConfig load() {
        LspConfig result = new LspConfig();

        Path userPath = Path.of(System.getProperty("user.home"), ".agentcli", "lsp.json");
        Path projectPath = Path.of(projectDir, ".agentcli", "lsp.json");

        LspConfig user = loadFile(userPath);
        if (user != null) {
            result.mergeFrom(user);
        }
        LspConfig project = loadFile(projectPath);
        if (project != null) {
            result.mergeFrom(project);
        }

        result.setProjectDir(projectDir);
        expandConfig(result, computeVars());
        return result;
    }

    /**
     * 从任意配置文件单独加载某个语言的 server 定义，并展开变量。
     * 用于 {@code /lsp start --config <path>}。
     *
     * @param path      配置文件路径
     * @param lang      目标语言标识
     * @param projectDir 变量展开用的项目根（${PROJECT_DIR}）
     * @return 展开后的 server 配置；文件不存在或无对应 server 时返回 null
     */
    public static LspServerConfig loadServerConfig(Path path, String lang, String projectDir) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            String content = Files.readString(path);
            if (content.isBlank()) {
                return null;
            }
            LspConfig cfg = LspConfig.fromJson(MAPPER.readTree(content));
            LspServerConfig sc = cfg.server(lang);
            if (sc == null && cfg.servers().size() == 1) {
                sc = cfg.servers().values().iterator().next();
            }
            if (sc == null) {
                return null;
            }
            sc.setLanguageId(lang);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.putAll(System.getenv());
            if (System.getenv("JDT_LS_HOME") != null) {
                vars.put("JDT_LS_HOME", System.getenv("JDT_LS_HOME"));
            }
            vars.put("PROJECT_DIR", projectDir == null ? "." : projectDir);
            if (System.getProperty("user.home") != null) {
                vars.put("HOME", System.getProperty("user.home"));
                vars.put("USER_HOME", System.getProperty("user.home"));
            }
            expandOne(sc, vars);
            return sc;
        } catch (Exception e) {
            return null;
        }
    }

    private static void expandOne(LspServerConfig sc, Map<String, String> vars) {
        if (sc.command() != null) {
            sc.setCommand(expand(sc.command(), vars));
        }
        if (sc.args() != null) {
            sc.setArgs(sc.args().stream().map(a -> expand(a, vars)).toList());
        }
        if (sc.env() != null) {
            Map<String, String> newEnv = new LinkedHashMap<>();
            sc.env().forEach((k, v) -> newEnv.put(k, expand(v, vars)));
            sc.setEnv(newEnv);
        }
        if (sc.rootPath() != null) {
            sc.setRootPath(expand(sc.rootPath(), vars));
        }
        // initializationOptions 已透传，无需展开
    }

    /**
     * 将配置写入指定路径。
     *
     * @param config 配置
     * @param path   目标文件路径
     * @throws IOException 写入失败
     */
    public static void save(LspConfig config, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config.toJson());
        Files.writeString(path, json);
    }

    /** @return 用户配置路径 */
    public static Path userConfigPath() {
        return Path.of(System.getProperty("user.home"), ".agentcli", "lsp.json");
    }

    /** @return 项目配置路径 */
    public Path projectConfigPath() {
        return Path.of(projectDir, ".agentcli", "lsp.json");
    }
}
