package com.agentcli.tool;

import com.agentcli.common.PolicyException;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.builtin.CreateProjectTool;
import com.agentcli.tool.builtin.ExecuteCommandTool;
import com.agentcli.tool.builtin.GlobFilesTool;
import com.agentcli.tool.builtin.GrepCodeTool;
import com.agentcli.tool.builtin.ListDirTool;
import com.agentcli.tool.builtin.ReadFileTool;
import com.agentcli.tool.builtin.WebSearchTool;
import com.agentcli.tool.builtin.WriteFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 工具注册表：管理所有可用工具，向 LLM 暴露工具定义，并按名称分发执行。
 *
 * 单工具走 {@link #executeTool}；同一轮多个 tool_call 走 {@link #executeTools}，
 * 在固定线程池内并行执行，结果仍按传入顺序返回，便于回灌消息历史。
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ToolContext context;
    private final long toolBatchTimeoutSeconds;

    public ToolRegistry() {
        this(System.getProperty("user.dir"));
    }

    public ToolRegistry(String projectPath) {
        this(projectPath, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(String projectPath, long toolBatchTimeoutSeconds) {
        this.context = new ToolContext(projectPath);
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
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
        register(new WebSearchTool());
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
        return doExecuteTool(name, argumentsJson);
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            String result = doExecuteTool(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "agentcli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        long startedAt = System.nanoTime();
                        String result = doExecuteTool(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private String doExecuteTool(String name, String argumentsJson) {
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

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
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
