package com.agentcli.agent;

import com.agentcli.context.ContextProfile;
import com.agentcli.prompt.PromptTemplateLoader;
import com.agentcli.prompt.PromptTemplateRenderer;
import com.agentcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent 系统提示词构建。
 *
 * <p>v0.16.0 起提示词正文外置到 classpath {@code prompts/*.md} 模板，便于调整措辞而不改代码。
 * 但模板是"锦上添花"而非"生死攸关"：任一模板缺失或读取失败，都会整体降级回
 * {@link #legacyPrompt} 的硬编码文本，行为与 v0.15.0 完全一致，绝不因为打包问题让 CLI 不可用。
 *
 * <p>注入原则：只放<b>低频稳定</b>字段（项目路径、工具数、模型名），
 * 不放 token 用量这类每轮都变的数值，否则会持续击穿上游的 prompt cache。
 */
public final class SystemPrompt {

    private static final String ROLE_TEMPLATE = "system_role.md";
    private static final String TOOLS_TEMPLATE = "system_tools.md";
    private static final String MEMORY_TEMPLATE = "system_memory.md";

    private static final String VAR_PROJECT_PATH = "project_path";
    private static final String VAR_TOOL_COUNT = "tool_count";
    private static final String VAR_MODEL_NAME = "model_name";
    private static final String VAR_MEMORY_CONTEXT = "memory_context";

    /**
     * 模板加载入口。抽成可替换的函数是为了让测试能模拟"模板缺失"以覆盖降级路径，
     * 生产路径始终是 {@link PromptTemplateLoader#load}。
     */
    static Function<String, String> templateLoader = PromptTemplateLoader::load;

    private SystemPrompt() {
    }

    public static String forAgent(ToolRegistry registry) {
        return forAgent(registry, "");
    }

    public static String forAgent(ToolRegistry registry, String memoryContext) {
        return forAgent(registry, memoryContext, null);
    }

    /**
     * 构建系统提示词。
     *
     * @param registry      工具注册表，用于取项目路径与工具数（可为 null）
     * @param memoryContext 记忆上下文，空白则整个记忆段丢弃
     * @param profile       上下文画像，可为 null；为 null 时退化为仅从 registry 取变量
     */
    public static String forAgent(ToolRegistry registry, String memoryContext, ContextProfile profile) {
        String projectPath = resolveProjectPath(registry, profile);

        String roleTemplate = loadTemplate(ROLE_TEMPLATE);
        String toolsTemplate = loadTemplate(TOOLS_TEMPLATE);
        String memoryTemplate = loadTemplate(MEMORY_TEMPLATE);
        if (roleTemplate == null || toolsTemplate == null || memoryTemplate == null) {
            return legacyPrompt(projectPath, memoryContext);
        }

        Map<String, String> vars = buildVariables(registry, profile, projectPath);

        List<String> segments = new ArrayList<>(3);
        addSegment(segments, renderTemplate(roleTemplate, vars));
        addSegment(segments, renderTemplate(toolsTemplate, vars));
        if (memoryContext != null && !memoryContext.isBlank()) {
            Map<String, String> memoryVars = new LinkedHashMap<>(vars);
            memoryVars.put(VAR_MEMORY_CONTEXT, memoryContext.trim());
            addSegment(segments, renderTemplate(memoryTemplate, memoryVars));
        }
        return String.join("\n\n", segments).trim();
    }

    /**
     * 构建模板变量表：三个键恒定存在，避免未赋值的占位符泄漏到提示词里。
     *
     * <p>有画像时以 {@link ContextProfile#toTemplateVariables()} 为权威来源（变量口径只此一处定义），
     * 无画像时退化为直接从 registry 取值。
     */
    private static Map<String, String> buildVariables(ToolRegistry registry, ContextProfile profile,
                                                      String projectPath) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (profile != null) {
            vars.putAll(profile.toTemplateVariables());
        }
        vars.put(VAR_PROJECT_PATH, projectPath);
        if (isBlank(vars.get(VAR_TOOL_COUNT))) {
            vars.put(VAR_TOOL_COUNT, String.valueOf(toolCountFromRegistry(registry)));
        }
        String model = vars.get(VAR_MODEL_NAME);
        vars.put(VAR_MODEL_NAME, model == null ? "" : model.trim());
        return vars;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String resolveProjectPath(ToolRegistry registry, ContextProfile profile) {
        if (profile != null && profile.projectPath() != null && !profile.projectPath().isBlank()) {
            return profile.projectPath();
        }
        return registry == null ? "." : registry.getProjectPath();
    }

    private static int toolCountFromRegistry(ToolRegistry registry) {
        if (registry == null) {
            return 0;
        }
        try {
            return registry.getToolDefinitions().size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 渲染单个模板段：先丢弃"变量值为空"的整行，再做占位符替换。
     *
     * <p>丢行这一步是必要的——例如未知模型时 {@code model_name} 为空串，
     * 直接渲染会留下"You are currently served by the  model."这种残句。
     */
    private static String renderTemplate(String template, Map<String, String> vars) {
        String pruned = dropLinesWithBlankVariables(template, vars);
        return PromptTemplateRenderer.render(pruned, vars).strip();
    }

    private static String dropLinesWithBlankVariables(String template, Map<String, String> vars) {
        List<String> blankKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                blankKeys.add("{{" + entry.getKey() + "}}");
            }
        }
        if (blankKeys.isEmpty()) {
            return template;
        }
        StringBuilder kept = new StringBuilder(template.length());
        for (String line : template.split("\n", -1)) {
            boolean drop = false;
            for (String placeholder : blankKeys) {
                if (line.contains(placeholder)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    private static void addSegment(List<String> segments, String segment) {
        if (segment != null && !segment.isBlank()) {
            segments.add(segment);
        }
    }

    private static String loadTemplate(String name) {
        Function<String, String> loader = templateLoader;
        if (loader == null) {
            return null;
        }
        try {
            return loader.apply(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * v0.15.0 的硬编码提示词，作为模板不可用时的兜底。
     *
     * <p>此方法的输出必须与 v0.16.0 之前逐字节一致，改动它等同于改动降级行为。
     */
    private static String legacyPrompt(String projectPath, String memoryContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are AgentCLI, a helpful coding assistant running in a local project workspace.
                Project root: %s

                Guidelines:
                - Use tools when they help answer accurately (read_file, grep_code, glob_files, web_search, save_memory, etc.).
                - Prefer grep_code/glob_files/read_file for code navigation before guessing.
                - Use save_memory only when the user explicitly asks to remember a stable fact or preference.
                - Keep answers concise and actionable; match the user's language (Chinese or English).
                - When a tool returns an error or policy rejection, explain it and try a safer alternative.
                """.formatted(projectPath));
        if (memoryContext != null && !memoryContext.isBlank()) {
            prompt.append("\n").append(memoryContext.trim()).append("\n");
        }
        return prompt.toString().trim();
    }
}
