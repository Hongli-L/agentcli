package com.agentcli.tool;

import com.agentcli.common.PolicyException;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.builtin.CreateProjectTool;
import com.agentcli.tool.builtin.ExecuteCommandTool;
import com.agentcli.tool.builtin.GlobFilesTool;
import com.agentcli.tool.builtin.GrepCodeTool;
import com.agentcli.tool.builtin.ListDirTool;
import com.agentcli.tool.builtin.ReadFileTool;
import com.agentcli.tool.builtin.WriteFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：管理所有可用工具，向 LLM 暴露工具定义，并按名称分发执行。
 *
 * 本批次为顺序执行版本——一次 chat 返回的多个 tool_call 由上层逐个调用 {@link #executeTool}。
 * 并行执行能力在后续批次（perf）中加入。
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ToolContext context;

    public ToolRegistry() {
        this(System.getProperty("user.dir"));
    }

    public ToolRegistry(String projectPath) {
        this.context = new ToolContext(projectPath);
        registerBuiltinTools();
    }

    private void registerBuiltinTools() {
        register(new ReadFileTool(context));
        register(new WriteFileTool(context));
        register(new ListDirTool(context));
        register(new GlobFilesTool(context));
        register(new GrepCodeTool(context));
        register(new ExecuteCommandTool(context));
        register(new CreateProjectTool(context));
    }

    /** 注册一个工具（同名覆盖）。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /** 切换工具运行的项目根路径。 */
    public void setProjectPath(String projectPath) {
        context.setProjectPath(projectPath);
    }

    public String getProjectPath() {
        return context.projectPath().toString();
    }

    /** 工具定义列表，供构造 LLM 请求时附带。 */
    public List<LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 执行一次工具调用。
     *
     * 统一出口处理三类结果：
     * - 策略拦截（PathGuard / CommandGuard / 文件大小上限）→ "🛡️ 策略拒绝: ..."
     * - 其他异常 → "工具执行失败: ..."
     * - 正常 → 工具自身返回文本（业务错误也以普通文本形式回灌给 LLM）
     */
    public String executeTool(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }
        try {
            Map<String, String> args = parseArguments(argumentsJson);
            return tool.execute(args);
        } catch (PolicyException e) {
            return "🛡️ 策略拒绝: " + e.getMessage();
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    private Map<String, String> parseArguments(String argumentsJson) throws Exception {
        Map<String, String> args = new LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return args;
        }
        JsonNode node = MAPPER.readTree(argumentsJson);
        if (!node.isObject()) {
            return args;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            args.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
        return args;
    }
}
