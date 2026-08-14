package com.agentcli.tool;

import com.agentcli.agent.concurrent.CancellationScope;
import com.agentcli.agent.concurrent.CancellationToken;
import com.agentcli.common.PolicyException;
import com.agentcli.common.SandboxPolicy;
import com.agentcli.common.UserFacingErrors;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.builtin.CreateProjectTool;
import com.agentcli.tool.builtin.ExecuteCommandTool;
import com.agentcli.tool.builtin.GlobFilesTool;
import com.agentcli.tool.builtin.GrepCodeTool;
import com.agentcli.tool.builtin.ListDirTool;
import com.agentcli.tool.builtin.RagSearchTool;
import com.agentcli.tool.builtin.ReadFileTool;
import com.agentcli.tool.builtin.WebFetchTool;
import com.agentcli.tool.builtin.WebSearchTool;
import com.agentcli.tool.builtin.WriteFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p><b>并发注册的防御性加固</b>：{@code ReActAgent} 构造函数会调用
 * {@code register(new SaveMemoryTool(...))}，多个 Agent 若并发构造会对同一张 map 并发写。
 * 裸 {@link LinkedHashMap} 的并发 {@code put} 可能破坏内部结构（丢数据甚至死循环），
 * 因此这里包一层 {@link Collections#synchronizedMap}，并在 {@link #getToolDefinitions()}
 * 里用手动同步块做保序快照——{@code synchronizedMap} 的<b>迭代不自动加锁</b>，
 * 不加同步块仍会抛 {@code ConcurrentModificationException}。
 *
 * <p>加固之外仍保留硬约定：worker 必须在编排线程上<b>串行预创建</b>后池化复用，
 * 不允许在并行任务体内 {@code new ReActAgent(...)}。加固是安全网，不是许可证。
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;

    private final Map<String, Tool> tools = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ToolContext context;
    private final SandboxPolicy sandboxPolicy;
    private final long toolBatchTimeoutSeconds;

    public ToolRegistry() {
        this(System.getProperty("user.dir"));
    }

    public ToolRegistry(String projectPath) {
        this(projectPath, SandboxPolicy.legacy());
    }

    public ToolRegistry(String projectPath, SandboxPolicy sandboxPolicy) {
        this(projectPath, sandboxPolicy, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(String projectPath, long toolBatchTimeoutSeconds) {
        this(projectPath, SandboxPolicy.legacy(), toolBatchTimeoutSeconds);
    }

    ToolRegistry(String projectPath, SandboxPolicy sandboxPolicy, long toolBatchTimeoutSeconds) {
        this.sandboxPolicy = sandboxPolicy == null ? SandboxPolicy.legacy() : sandboxPolicy;
        this.context = new ToolContext(projectPath, this.sandboxPolicy);
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        registerBuiltinTools();
    }

    /** 当前生效的沙箱策略（命令 deny-by-default / 单轮配额 / 写确认 / 受保护目录）。 */
    public SandboxPolicy sandboxPolicy() {
        return sandboxPolicy;
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
        register(new WebFetchTool());
        register(new RagSearchTool(context));
    }

    /** 注册一个工具（同名覆盖）。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 移除指定名称的工具。 */
    public void unregister(String name) {
        tools.remove(name);
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

    /**
     * 工具定义列表，供构造 LLM 请求时附带。
     *
     * <p>先在同步块内做<b>保序</b>快照（{@link LinkedHashMap} 的插入序即为工具展示序），
     * 出块后再流式转换：转换过程可能较慢，不该占着锁不放。
     */
    public List<LlmClient.Tool> getToolDefinitions() {
        List<Tool> snapshot;
        synchronized (tools) {
            snapshot = new ArrayList<>(tools.values());
        }
        return snapshot.stream()
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
        // 取消安全点：整批还没开跑就已被取消，一个副作用都不放出去。
        if (CancellationScope.isCancelled()) {
            return allCancelled(invocations);
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            String result = doExecuteTool(invocation.name(), invocation.argumentsJson());
            if (CancellationScope.isCancelled()) {
                return List.of(ToolExecutionResult.cancelled(invocation));
            }
            return List.of(ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "agentcli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        // 工具批次的子线程不继承 ThreadLocal，改为在提交前捕获父线程令牌并显式下传，
        // 让每个工具体也能在自己的线程上看到同一份取消状态。
        CancellationToken token = CancellationScope.current();
        // L3 句柄：取消时直接 shutdownNow 打断整批工具线程；finally 必定注销，避免令牌长期持有已废弃的池。
        Runnable hook = token == null ? null : token.onCancel(executor::shutdownNow);

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        long startedAt = System.nanoTime();
                        return CancellationScope.call(token, () -> {
                            if (CancellationScope.isCancelled()) {
                                return ToolExecutionResult.cancelled(invocation);
                            }
                            String result = doExecuteTool(invocation.name(), invocation.argumentsJson());
                            return ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt));
                        });
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            boolean cancelled = CancellationScope.isCancelled();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    // 被取消掐断与被批次超时掐断在 Future 上无法区分，靠令牌状态判定语义
                    results.add(cancelled
                            ? ToolExecutionResult.cancelled(invocation)
                            : ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(cancelled
                            ? ToolExecutionResult.cancelled(invocation)
                            : ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(cancelled
                            ? ToolExecutionResult.cancelled(invocation)
                            : ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (CancellationScope.isCancelled()) {
                return allCancelled(invocations);
            }
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            if (hook != null) {
                token.removeHook(hook);
            }
            executor.shutdownNow();
        }
    }

    private static List<ToolExecutionResult> allCancelled(List<ToolInvocation> invocations) {
        return invocations.stream()
                .map(ToolExecutionResult::cancelled)
                .toList();
    }

    protected String doExecuteTool(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }
        try {
            Map<String, String> args = parseArguments(argumentsJson);
            return tool.execute(args);
        } catch (PolicyException e) {
            return UserFacingErrors.policyReject(e.getMessage());
        } catch (Exception e) {
            return UserFacingErrors.toolFailure(e.getMessage());
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
        JsonNode node;
        try {
            node = MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数必须是合法 JSON");
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            args.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
        return args;
    }
}
