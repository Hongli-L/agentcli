package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.agent.concurrent.AgentTaskExecutor;
import com.agentcli.agent.concurrent.AgentTaskView;
import com.agentcli.agent.orchestration.AgentOrchestrator;
import com.agentcli.agent.orchestration.OrchestrationConfig;
import com.agentcli.agent.orchestration.OrchestrationResult;
import com.agentcli.audit.AuditLog;
import com.agentcli.audit.AuditLlmClient;
import com.agentcli.audit.OrchestrationTrace;
import com.agentcli.common.AnsiStyle;
import com.agentcli.common.AppConfig;
import com.agentcli.context.ContextProfile;
import com.agentcli.context.TokenUsageFormatter;
import com.agentcli.hitl.HitlHandler;
import com.agentcli.hitl.HitlToolRegistry;
import com.agentcli.llm.LlmClient;
import com.agentcli.llm.LlmClientFactory;
import com.agentcli.llm.PromptCacheMode;
import com.agentcli.mcp.McpServerManager;
import com.agentcli.mcp.mention.ImageMentionParser;
import com.agentcli.mcp.mention.MentionResolver;
import com.agentcli.memory.MemoryEntry;
import com.agentcli.plan.PlanExecuteAgent;
import com.agentcli.plan.ExecutionPlan;
import com.agentcli.plan.PlanStore;
import com.agentcli.plan.Task;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;
import com.agentcli.lsp.LspCommandHandler;
import com.agentcli.lsp.LspDiagnosticsFormatter;
import com.agentcli.lsp.LspServerManager;
import com.agentcli.lsp.config.LspConfig;
import com.agentcli.rag.RagCommandHandler;
import com.agentcli.store.SqliteStore;
import com.agentcli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * 交互式 REPL：读取用户输入，分发斜杠命令或交给 {@link ReActAgent} 处理。
 */
public class InteractiveSession {

    private final ReActAgent agent;
    private final AppConfig config;
    private final AuditLog auditLog;
    private LlmClient llmClient;
    private McpServerManager mcpManager;
    private final SkillRegistry skillRegistry;
    private final SkillStateStore skillStateStore;
    private LspServerManager lspManager;
    private LspCommandHandler lspHandler;
    private MentionResolver mentionResolver;
    private RagCommandHandler ragHandler;
    private boolean ragHandlerResolved;

    /**
     * v0.20.0：会话级并发执行器，{@code /team} {@code /cancel} {@code /tasks} 三命令共用。
     *
     * <p><b>为什么必须是会话级而不是每次 {@code /team} 新建</b>：{@code /cancel} 与 {@code /tasks}
     * 要能看见「正在跑的那一轮编排」的任务。若每次编排自带私有执行器并在结束时关掉，
     * 任务注册表随之消失，两个命令永远只能打印空表。
     *
     * <p>懒加载：没用过 {@code /team} 的会话不会创建线程池，纯对话场景零额外线程。
     */
    private AgentTaskExecutor taskExecutor;

    /** v0.20.0：编排轨迹落盘器，允许为 {@code null}（未启用 SQLite 审计时）。 */
    private OrchestrationTrace orchestrationTrace;

    /** v0.20.0：编排参数。默认取 {@link OrchestrationConfig#defaults()}，测试可替换为小超时配置。 */
    private OrchestrationConfig orchestrationConfig = OrchestrationConfig.defaults();

    public InteractiveSession(ReActAgent agent, LlmClient llmClient, AppConfig config) {
        this(agent, llmClient, config, null, null, null);
    }

    public InteractiveSession(ReActAgent agent, LlmClient llmClient, AppConfig config, AuditLog auditLog) {
        this(agent, llmClient, config, auditLog, null, null);
    }

    public InteractiveSession(ReActAgent agent, LlmClient llmClient, AppConfig config,
                             AuditLog auditLog, SkillRegistry skillRegistry, SkillStateStore skillStateStore) {
        this.agent = agent;
        this.llmClient = llmClient;
        this.config = config;
        this.auditLog = auditLog;
        this.skillRegistry = skillRegistry;
        this.skillStateStore = skillStateStore;
    }

    public void setMcpManager(McpServerManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    /** v0.20.0：注入编排轨迹落盘器；{@code null} 表示不落盘。 */
    public void setOrchestrationTrace(OrchestrationTrace trace) {
        this.orchestrationTrace = trace;
    }

    /** v0.20.0：覆盖编排参数（测试用小超时；生产走 {@link OrchestrationConfig#defaults()}）。 */
    public void setOrchestrationConfig(OrchestrationConfig config) {
        this.orchestrationConfig = config == null ? OrchestrationConfig.defaults() : config;
    }

    /** v0.14.0：注入 @mention 解析器，供自然语言轮（NONE）解析自由文本。 */
    public void setMentionResolver(MentionResolver mentionResolver) {
        this.mentionResolver = mentionResolver;
    }

    /**
     * v0.18.0：注入 RAG 命令门面。
     *
     * <p>生产环境无需调用——{@link #ragHandler(PrintStream)} 会在首次使用 {@code /index}
     * {@code /search} {@code /graph} 时懒加载；本方法供测试替换为桩实现。
     */
    public void setRagCommandHandler(RagCommandHandler handler) {
        this.ragHandler = handler;
        this.ragHandlerResolved = true;
    }

    public void setLspManager(LspServerManager lspManager, LspConfig lspConfig) {
        this.lspManager = lspManager;
        if (lspManager != null && lspConfig != null) {
            this.lspHandler = new LspCommandHandler(
                    lspManager, lspConfig, new LspDiagnosticsFormatter(),
                    lspConfig.projectDir());
        }
    }

    public void run(ReplReader input, PrintStream out) throws IOException {
        try {
            printWelcome(out);
            while (true) {
                String line = input.readLine("You> ");
                if (line == null) {
                    out.println();
                    break;
                }

                CliCommandParser.ParsedCommand command = CliCommandParser.parse(line);
                if (handleCommand(command, out)) {
                    if (command.type() == CliCommandParser.CommandType.EXIT) {
                        return;
                    }
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }

                // v0.14.0：仅对自然语言轮（NONE）解析 @mention；/命令 轮不解析以免破坏命令语法。
                if (command.type() == CliCommandParser.CommandType.NONE) {
                    if (mentionResolver != null) {
                        line = mentionResolver.resolve(line);
                    }
                    // v0.26.0：把 @image:<path> / @clipboard 转成多模态用户消息（无图片时退回纯文本，行为不变）。
                    Path baseDir = projectBaseDir();
                    LlmClient.Message userMsg = ImageMentionParser.userMessage(line, baseDir, mentionResolver);
                    runTurn(userMsg, out);
                } else {
                    runTurn(line, out);
                }
            }
        } finally {
            if (lspManager != null) {
                lspManager.stopAll();
            }
            // v0.20.0：退出前关停编排线程池。池线程虽是 daemon（不会阻止 JVM 退出），
            // 但显式关停能取消在跑任务并释放其持有的 HTTP 连接，避免退出时刷一片超时告警。
            closeTaskExecutor();
            input.close();
        }
    }

    /** 执行一轮 Agent 对话并输出到终端（纯文本用户输入）。 */
    public void runTurn(String userInput, PrintStream out) {
        StreamingConsoleListener listener = new StreamingConsoleListener(out);
        agent.setListener(listener);
        String response = agent.run(userInput);
        boolean streamed = listener.hasStreamedContent();
        listener.finishTurn();
        if (!streamed && response != null && !response.isBlank()) {
            out.println(response);
        }
    }

    /** 执行一轮 Agent 对话（多模态用户消息，如携带 @image 图片部件）。 */
    public void runTurn(LlmClient.Message userMessage, PrintStream out) {
        StreamingConsoleListener listener = new StreamingConsoleListener(out);
        agent.setListener(listener);
        String response = agent.run(userMessage);
        boolean streamed = listener.hasStreamedContent();
        listener.finishTurn();
        if (!streamed && response != null && !response.isBlank()) {
            out.println(response);
        }
    }

    /** 图片 @mention 的相对路径解析基目录（项目根）；未配置时返回 null，由解析器退化为 user.dir。 */
    private Path projectBaseDir() {
        String pp = agent.getToolRegistry().getProjectPath();
        return pp == null ? null : Path.of(pp);
    }

    /**
     * @return true 表示输入已被命令消费（含 exit，调用方应在 EXIT 时结束会话）
     */
    boolean handleCommand(CliCommandParser.ParsedCommand command, PrintStream out) {
        return switch (command.type()) {
            case EXIT -> {
                out.println("再见。");
                yield true;
            }
            case CLEAR -> {
                agent.clearHistory();
                out.println("对话历史已清空。");
                yield true;
            }
            case HELP -> {
                printSessionHelp(out, command.payload());
                yield true;
            }
            case MODEL -> {
                switchModel(command.payload(), out);
                yield true;
            }
            case COMPACT -> {
                ReActAgent.CompactionResult result = agent.compactHistoryNow();
                if (result.error() != null) {
                    out.println("压缩失败: " + result.error());
                } else if (result.compacted()) {
                    out.println(String.format("已压缩对话历史: %d -> %d tokens",
                            result.beforeTokens(), result.afterTokens()));
                } else {
                    out.println("当前上下文无需压缩。");
                }
                yield true;
            }
            case HITL -> {
                handleHitl(command.payload(), out);
                yield true;
            }
            case MCP -> {
                handleMcp(command.payload(), out);
                yield true;
            }
            case PLAN -> {
                runPlan(command.payload(), out);
                yield true;
            }
            case MEMORY_STATUS -> {
                out.println(agent.getMemoryManager().getSystemStatus());
                yield true;
            }
            case MEMORY_LIST -> {
                printMemoryList(out);
                yield true;
            }
            case MEMORY_CLEAR -> {
                agent.getMemoryManager().clearLongTerm();
                out.println("长期记忆已清空。");
                yield true;
            }
            case MEMORY_SEARCH -> {
                printMemorySearch(command.payload(), out);
                yield true;
            }
            case MEMORY_DELETE -> {
                if (command.payload() == null || command.payload().isBlank()) {
                    out.println("用法: /memory delete <记忆ID>");
                    yield true;
                }
                boolean deleted = agent.getMemoryManager().deleteLongTerm(command.payload());
                out.println(deleted ? "已删除记忆: " + command.payload() : "未找到记忆: " + command.payload());
                yield true;
            }
            case AUDIT -> {
                handleAudit(command.payload(), out);
                yield true;
            }
            case SKILL -> {
                handleSkill(command.payload(), out);
                yield true;
            }
            case LSP -> {
                handleLsp(command.payload(), out);
                yield true;
            }
            case INDEX -> {
                handleIndex(command.payload(), out);
                yield true;
            }
            case SEARCH -> {
                handleSearch(command.payload(), out);
                yield true;
            }
            case GRAPH -> {
                handleGraph(command.payload(), out);
                yield true;
            }
            case STATUS -> {
                handleStatus(out);
                yield true;
            }
            case USAGE -> {
                handleUsage(out);
                yield true;
            }
            case TEAM -> {
                handleTeam(command.payload(), out);
                yield true;
            }
            case CANCEL -> {
                handleCancel(command.payload(), out);
                yield true;
            }
            case TASKS -> {
                handleTasks(out);
                yield true;
            }
            case NONE -> false;
        };
    }

    /** {@code /status}：打印当前会话的上下文画像，并追加当前 provider 的提示词缓存模式。 */
    private void handleStatus(PrintStream out) {
        ContextProfile profile = captureProfile(out);
        if (profile != null) {
            out.println(profile.render());
            out.println(renderPromptCacheLine());
        }
    }

    /**
     * 渲染提示词缓存一行，列宽与 {@link ContextProfile#render()} 的标签列（10 显示宽度）对齐。
     *
     * <p>{@code 提示词缓存} 恰为 5 个全角字符（显示宽度 10），故无需补空格。
     */
    private String renderPromptCacheLine() {
        return AnsiStyle.subtle("  提示词缓存: ") + describePromptCache();
    }

    /**
     * 描述当前 provider 的提示词缓存能力。
     *
     * <p>与 {@link #captureProfile} 同样按「状态类命令不许抛异常」处理：客户端缺失或
     * 元数据取值抛错时降级为友好文案，而不是把堆栈糊到用户脸上。
     */
    private String describePromptCache() {
        boolean supported;
        String mode;
        try {
            supported = llmClient != null && llmClient.supportsPromptCaching();
            mode = llmClient == null ? null : llmClient.promptCacheMode();
        } catch (RuntimeException e) {
            return "未知";
        }
        if (!supported || mode == null || mode.isBlank() || PromptCacheMode.NONE.equals(mode)) {
            return "无（该 provider 未启用）";
        }
        return AnsiStyle.emphasis(mode);
    }

    /** {@code /usage}：打印累计 token 用量与费用估算。 */
    private void handleUsage(PrintStream out) {
        ContextProfile profile = captureProfile(out);
        if (profile != null) {
            out.println(TokenUsageFormatter.format(profile));
        }
    }

    /**
     * 采集上下文画像。任何异常都转成一行友好提示，
     * 状态类命令不该因为某个数据源抽风就把堆栈糊到用户脸上。
     */
    private ContextProfile captureProfile(PrintStream out) {
        try {
            return ContextProfile.capture(llmClient, agent.getToolRegistry(), mcpManager, agent.getMemoryManager());
        } catch (RuntimeException e) {
            out.println("无法采集会话状态: " + e.getMessage());
            return null;
        }
    }

    private void printMemoryList(PrintStream out) {
        var entries = agent.getMemoryManager().listLongTerm();
        if (entries.isEmpty()) {
            out.println("长期记忆为空。");
            return;
        }
        out.println("长期记忆 (" + entries.size() + " 条):");
        for (MemoryEntry entry : entries) {
            out.printf("  [%s] %s (%s)%n", entry.getId(), entry.getContent(),
                    longTermMemoryScope(entry));
        }
    }

    private void printMemorySearch(String query, PrintStream out) {
        if (query == null || query.isBlank()) {
            out.println("用法: /memory search <关键词>");
            return;
        }
        var entries = agent.getMemoryManager().searchLongTerm(query, 10);
        if (entries.isEmpty()) {
            out.println("未找到匹配记忆。");
            return;
        }
        out.println("搜索 \"" + query + "\" 命中 " + entries.size() + " 条:");
        for (MemoryEntry entry : entries) {
            out.printf("  [%s] %s%n", entry.getId(), entry.getContent());
        }
    }

    private static String longTermMemoryScope(MemoryEntry entry) {
        return com.agentcli.memory.LongTermMemory.scopeOf(entry);
    }

    private void switchModel(String provider, PrintStream out) {
        if (provider == null || provider.isBlank()) {
            out.println("当前模型: " + llmClient.getProviderName() + " / " + llmClient.getModelName());
            out.println("用法: /model glm | deepseek | openai");
            return;
        }
        String normalized = CliArgs.normalizeProvider(provider);
        LlmClient next = LlmClientFactory.create(normalized, config);
        if (next == null) {
            out.println("无法切换到 " + provider + "：请检查 API Key 是否已配置。");
            return;
        }
        llmClient = auditLog != null ? new AuditLlmClient(next, auditLog) : next;
        agent.setLlmClient(llmClient);
        config.setDefaultProvider(normalized);
        config.save();
        out.println("已切换: " + next.getProviderName() + " / " + next.getModelName());
    }

    private void handleHitl(String payload, PrintStream out) {
        HitlHandler handler = getHitlHandler();
        if (handler == null) {
            out.println("当前未启用 HITL 审批（工具注册表不是 HitlToolRegistry）。");
            return;
        }
        if (payload == null || payload.isBlank()) {
            out.println("HITL 审批状态: " + (handler.isEnabled() ? "已启用" : "已关闭"));
            out.println("用法: /hitl on | off | clear");
            return;
        }
        String normalized = payload.trim().toLowerCase();
        switch (normalized) {
            case "on" -> {
                handler.setEnabled(true);
                out.println("HITL 审批已启用。危险操作（写文件/执行命令/创建项目）将需要人工确认。");
            }
            case "off" -> {
                handler.setEnabled(false);
                out.println("HITL 审批已关闭。");
            }
            case "clear" -> {
                handler.clearApprovedAll();
                out.println("已清除全部放行记录。");
            }
            default -> out.println("未知 HITL 子命令: " + payload + "。用法: /hitl on | off | clear");
        }
    }

    private void handleAudit(String payload, PrintStream out) {
        if (auditLog == null) {
            out.println("审计未启用（SQLite 持久化不可用）。");
            return;
        }
        int limit = 20;
        if (payload != null && !payload.isBlank()) {
            try {
                limit = Integer.parseInt(payload.trim());
            } catch (NumberFormatException e) {
                out.println("用法: /audit [数量] —— 例如 /audit 50");
                return;
            }
        }
        var entries = auditLog.query(limit);
        if (entries.isEmpty()) {
            out.println("暂无审计记录。");
            return;
        }
        out.println("最近 " + entries.size() + " 条审计记录:");
        for (var e : entries) {
            out.println("  " + e.summaryLine());
        }
    }

    private void handleSkill(String payload, PrintStream out) {
        if (skillRegistry == null) {
            out.println("Skill 系统未初始化（请联系开发者）。");
            return;
        }
        if (payload == null || payload.isBlank()) {
            out.println(SkillCommandHandler.list(skillRegistry));
            return;
        }
        String[] parts = payload.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (sub) {
            case "list" -> out.println(SkillCommandHandler.list(skillRegistry));
            case "show" -> out.println(SkillCommandHandler.show(skillRegistry, arg));
            case "on", "enable" ->
                    out.println(SkillCommandHandler.enable(skillRegistry, skillStateStore, arg));
            case "off", "disable" ->
                    out.println(SkillCommandHandler.disable(skillRegistry, skillStateStore, arg));
            case "reload" -> {
                skillRegistry.reload();
                out.println(SkillCommandHandler.startupSummary(skillRegistry));
                out.println(SkillCommandHandler.list(skillRegistry));
            }
            default -> out.println("未知 skill 子命令: " + sub
                    + "。用法: /skill [list|show <name>|on <name>|off <name>|reload]");
        }
    }

    private void handleLsp(String payload, PrintStream out) {
        if (lspManager == null || lspHandler == null) {
            out.println("LSP 服务器管理器未初始化（使用 --lsp 参数启动）。");
            return;
        }
        String cmd = (payload == null || payload.isBlank()) ? "lsp" : "lsp " + payload;
        out.println(lspHandler.handle(cmd));
    }

    /** {@code /index}：进度回调直接接到 {@code out}，让长耗时索引过程实时可见。 */
    private void handleIndex(String payload, PrintStream out) {
        RagCommandHandler handler = ragHandler(out);
        if (handler != null) {
            out.println(handler.handleIndex(payload, out::println));
        }
    }

    /** {@code /search}：语义 + 关键词混合检索。 */
    private void handleSearch(String payload, PrintStream out) {
        RagCommandHandler handler = ragHandler(out);
        if (handler != null) {
            out.println(handler.handleSearch(payload));
        }
    }

    /** {@code /graph}：类关系图谱，不依赖 embedding。 */
    private void handleGraph(String payload, PrintStream out) {
        RagCommandHandler handler = ragHandler(out);
        if (handler != null) {
            out.println(handler.handleGraph(payload));
        }
    }

    /**
     * 懒加载 RAG 门面：只在首次使用三命令时才打开索引库，避免为没用过 RAG 的会话付出建库开销。
     *
     * <p>初始化失败只记一次并每次给出单行原因，不重试——失败原因（库文件损坏 / 无写权限）
     * 通常在会话生命周期内不会自愈，反复重试只会拖慢每一次命令。
     */
    private RagCommandHandler ragHandler(PrintStream out) {
        if (!ragHandlerResolved) {
            ragHandlerResolved = true;
            ragHandler = RagCommandHandler.create(config, agent.getToolRegistry().getProjectPath());
        }
        if (ragHandler == null) {
            out.println("❌ RAG 索引不可用: " + RagCommandHandler.lastCreateFailure());
        }
        return ragHandler;
    }

    private void handleMcp(String payload, PrintStream out) {
        if (mcpManager == null) {
            out.println("MCP 服务器管理器未初始化（使用 --mcp 参数启动）。");
            return;
        }
        if (payload == null || payload.isBlank()) {
            out.println(mcpManager.formatStatus());
            out.println("用法: /mcp status | logs <name>");
            return;
        }
        String[] parts = payload.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        switch (sub) {
            case "status" -> out.println(mcpManager.formatStatus());
            case "logs" -> {
                if (arg.isBlank()) {
                    out.println("用法: /mcp logs <server_name>");
                } else {
                    out.println(mcpManager.logs(arg));
                }
            }
            default -> out.println("未知 MCP 子命令: " + sub + "。用法: /mcp status | logs <name>");
        }
    }

    private void runPlan(String payload, PrintStream out) {
        if (payload == null || payload.isBlank()) {
            out.println("用法: /plan <目标> —— 例如 /plan 先读 main.java 再写单元测试");
            out.println("      /plan resume   —— 恢复最近一个未完成(崩溃/中断)的 plan 并续跑");
            return;
        }
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, agent.getToolRegistry());
        StreamingConsoleListener listener = new StreamingConsoleListener(out);
        planAgent.setListener(listener);
        planAgent.setOut(out);
        try {
            PlanStore planStore = new PlanStore(SqliteStore.defaultStore());
            planAgent.setPlanStore(planStore);
            if ("resume".equalsIgnoreCase(payload.trim())) {
                String planId = planStore.findResumablePlanId();
                if (planId == null) {
                    out.println("没有可恢复的 plan（最近一个 plan 已完成 / 失败 / 已取消）。");
                    return;
                }
                ExecutionPlan plan = planStore.loadPlan(planId);
                if (plan == null) {
                    out.println("plan " + planId + " 无法恢复。");
                    return;
                }
                // 续跑：把失败任务重置为待执行以便重试
                plan.getAllTasks().stream()
                        .filter(t -> t.getStatus() == Task.TaskStatus.FAILED)
                        .forEach(t -> t.restoreState(Task.TaskStatus.PENDING, null, null));
                out.println("[恢复 plan " + planId + "]");
                String result = planAgent.executePlan(plan);
                listener.finishTurn();
                if (result != null && !result.isBlank()) {
                    out.println(result);
                }
            } else {
                String result = planAgent.run(payload);
                listener.finishTurn();
                if (result != null && !result.isBlank()) {
                    out.println(result);
                }
            }
        } catch (SQLException e) {
            listener.finishTurn();
            out.println("Plan 存储初始化失败: " + e.getMessage());
        } catch (IOException e) {
            listener.finishTurn();
            out.println("计划执行失败: " + e.getMessage());
        }
    }

    /**
     * {@code /team <目标>}：多 Agent 协作编排。
     *
     * <p><b>与 {@code /plan} 的边界</b>：两者都调用同一个 {@code Planner} 做任务拆解，区别在执行阶段——
     * {@code /plan} 由<b>单个</b> Agent 顺序跑完全部子任务，共享一份对话历史；
     * {@code /team} 把子任务分发给<b>多个隔离的 worker</b> 并行跑，各自独立历史，末尾再由 reviewer
     * 复核聚合结果。子任务之间彼此独立时 {@code /team} 更快，需要连续上下文推理时 {@code /plan} 更稳。
     *
     * <p>编排器每次新建但共享会话级执行器：新建成本只是预创建 3 个 worker（无网络 IO），
     * 换来的是「本轮参数与 worker 状态干净」；共享执行器则保证 {@code /tasks} {@code /cancel} 可见。
     */
    private void handleTeam(String goal, PrintStream out) {
        if (goal == null || goal.isBlank()) {
            out.println("用法: /team <目标> —— 例如 /team 分析项目结构并总结三个改进点");
            out.println("      与 /plan 的区别：/plan 单 Agent 顺序执行，/team 多 Agent 并行协作后复核");
            return;
        }
        AgentTaskExecutor executor = taskExecutor();
        // 新一轮编排开始前清掉上一轮的终态任务，避免 /tasks 无限增长；
        // 本轮结束后不清，用户仍能用 /tasks 回看刚跑完的这一轮。
        executor.clearFinished();

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, agent.getToolRegistry(), orchestrationConfig, executor)) {
            orchestrator.setTrace(orchestrationTrace);
            orchestrator.setOut(out);
            OrchestrationResult result = orchestrator.run(goal);
            out.println();
            out.println(result.render());
            if (result.degraded() && result.failedCount() > 0) {
                out.println(AnsiStyle.subtle(
                        "提示: 有 " + result.failedCount() + " 个子任务未成功，可用 /tasks 查看详情。"));
            }
        } catch (RuntimeException e) {
            // 编排门面本身已把失败收敛为 OrchestrationResult.failed，这里兜住构造期异常
            // （如 worker 预创建失败），保证一条命令出错不掀翻整个 REPL。
            out.println("❌ 编排启动失败: " + describeError(e));
        }
    }

    /**
     * {@code /cancel [taskId]}：无参取消全部在跑任务，带参精确取消一个。
     *
     * <p><b>使用时机</b>：本版 {@code /team} 是阻塞式命令（REPL 线程等编排跑完），所以
     * {@code /cancel} 实际生效于两种场景——① 上一轮编排被 Ctrl+C 打断后残留的任务；
     * ② 后续版本把编排后台化之后的运行中取消。取消是三层同时下手（协作标志 / 线程中断 /
     * HTTP 句柄关闭），因此即使 worker 正卡在 LLM 长响应上也能立刻收手。
     */
    private void handleCancel(String taskId, PrintStream out) {
        if (taskExecutor == null) {
            out.println("暂无编排任务。");
            return;
        }
        if (taskId == null || taskId.isBlank()) {
            int count = taskExecutor.cancelAll();
            out.println(count == 0 ? "没有正在运行的任务。" : "已请求取消 " + count + " 个任务。");
            return;
        }
        String normalized = taskId.trim();
        boolean hit = taskExecutor.cancel(normalized);
        out.println(hit ? "已请求取消 " + normalized + "。" : "未找到运行中的任务: " + normalized);
    }

    /** {@code /tasks}：列出本会话最近一轮编排的全部任务快照（含已完成）。 */
    private void handleTasks(PrintStream out) {
        if (taskExecutor == null || taskExecutor.taskCount() == 0) {
            out.println("暂无编排任务。");
            return;
        }
        List<AgentTaskView> views = taskExecutor.snapshot();
        if (views.isEmpty()) {
            out.println("暂无编排任务。");
            return;
        }
        out.println("编排任务 (" + views.size() + " 个):");
        for (AgentTaskView view : views) {
            out.println("  " + view.summaryLine());
        }
    }

    /**
     * 懒加载会话级执行器：只有真正用过 {@code /team} 的会话才创建线程池。
     *
     * <p>池尺寸取自 {@link #orchestrationConfig}，与 worker 池尺寸对齐，
     * 保证「排队等线程」与「排队等 worker」不会同时成为瓶颈。
     */
    private AgentTaskExecutor taskExecutor() {
        if (taskExecutor == null) {
            taskExecutor = new AgentTaskExecutor(orchestrationConfig.maxParallel());
        }
        return taskExecutor;
    }

    /**
     * 关停会话级编排执行器（幂等）。
     *
     * <p>正常路径由 {@link #run} 的 {@code finally} 调用；单测直接驱动 {@code handleCommand}
     * 时绕过了 {@code run}，需要显式收尾，否则空闲池线程会一直挂在 JVM 里干扰线程泄漏断言。
     */
    void closeTaskExecutor() {
        if (taskExecutor != null) {
            taskExecutor.close();
            taskExecutor = null;
        }
    }

    private static String describeError(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private HitlHandler getHitlHandler() {
        if (agent.getToolRegistry() instanceof HitlToolRegistry hitlRegistry) {
            return hitlRegistry.getHitlHandler();
        }
        return null;
    }

    private void printWelcome(PrintStream out) {
        out.println();
        out.println(AnsiStyle.heading("AgentCLI v" + Launcher.VERSION));
        out.println(AnsiStyle.subtle("模型: " + llmClient.getProviderName() + " / " + llmClient.getModelName()
                + "  ·  项目: " + agent.getToolRegistry().getProjectPath()));
        if (mcpManager != null && !mcpManager.servers().isEmpty()) {
            out.println(AnsiStyle.subtle("MCP: " + mcpManager.servers().size() + " 个服务器已配置"));
        }
        out.println(AnsiStyle.subtle("输入 /help 查看命令，/exit 退出。"));
    }

    /** 帮助条目：命令文本 + 描述。 */
    private record HelpEntry(String command, String desc) {}

    /** 打印完整会话帮助（无关键词过滤）。 */
    static void printSessionHelp(PrintStream out) {
        printSessionHelp(out, null);
    }

    /**
     * 打印会话帮助；keyword 非空时仅显示命令或描述中包含该关键词的条目（大小写不敏感）。
     */
    static void printSessionHelp(PrintStream out, String keyword) {
        List<HelpEntry> slash = List.of(
                new HelpEntry("/help, /?", "显示本帮助"),
                new HelpEntry("/clear", "清空当前对话历史"),
                new HelpEntry("/model [provider]", "查看或切换模型 (glm/deepseek/openai)"),
                new HelpEntry("/memory, /mem", "查看记忆状态"),
                new HelpEntry("/memory list", "列出长期记忆"),
                new HelpEntry("/memory search Q", "搜索长期记忆"),
                new HelpEntry("/memory delete ID", "删除指定记忆"),
                new HelpEntry("/memory clear", "清空长期记忆"),
                new HelpEntry("/status", "查看会话上下文画像（模型/工具/记忆/token）"),
                new HelpEntry("/usage", "查看累计 token 用量与费用估算"),
                new HelpEntry("/compact", "手动压缩对话历史"),
                new HelpEntry("/hitl [on|off|clear]", "查看/切换/重置人工审批"),
                new HelpEntry("/plan <目标>", "先规划再分步执行复杂任务"),
                new HelpEntry("/plan resume", "恢复最近一个未完成的 plan 并续跑"),
                new HelpEntry("/team <目标>", "多 Agent 并行协作完成目标并复核结果"),
                new HelpEntry("/tasks", "查看编排任务状态"),
                new HelpEntry("/cancel [任务ID]", "取消全部 / 指定编排任务"),
                new HelpEntry("/mcp [status|logs]", "MCP 服务器状态与日志"),
                new HelpEntry("/audit [数量]", "查看最近的 LLM 调用与审批审计记录"),
                new HelpEntry("/skill [list]", "列出已加载 skill 与启用状态"),
                new HelpEntry("/skill show <name>", "查看某个 skill 的完整 SKILL.md"),
                new HelpEntry("/skill on/off <name>", "启用 / 禁用某个 skill"),
                new HelpEntry("/skill reload", "重新扫描 skill 目录"),
                new HelpEntry("/lsp [start|stop|status|diagnostics|config]", "LSP 诊断集成"),
                new HelpEntry("/index [路径]", "建立/重建代码语义索引与类关系图谱"),
                new HelpEntry("/search <自然语言>", "语义 + 关键词混合检索代码"),
                new HelpEntry("/graph <类名>", "查看类的继承/实现/包含/调用关系"),
                new HelpEntry("/exit, /quit", "退出交互模式"));
        List<HelpEntry> args = List.of(
                new HelpEntry("-p, --prompt TEXT", "单次提问后退出"),
                new HelpEntry("--provider NAME", "指定默认模型 provider"),
                new HelpEntry("--project, -C DIR", "指定项目根目录"));
        out.println();
        printHelpSection(out, "斜杠命令:", slash, keyword);
        printHelpSection(out, "启动参数:", args, keyword);
    }

    private static void printHelpSection(PrintStream out, String header, List<HelpEntry> entries, String keyword) {
        List<HelpEntry> matched = (keyword == null || keyword.isBlank())
                ? entries
                : entries.stream().filter(e -> helpMatches(e, keyword)).toList();
        if (matched.isEmpty()) {
            if (keyword != null && !keyword.isBlank()) {
                out.println(AnsiStyle.subtle("没有匹配 \"" + keyword + "\" 的命令。输入 /help 查看全部。"));
            }
            return;
        }
        out.println(header);
        int width = entries.stream().mapToInt(e -> e.command().length()).max().orElse(0);
        for (HelpEntry e : matched) {
            int pad = Math.max(2, width - e.command().length() + 2);
            out.println("  " + e.command() + " ".repeat(pad) + e.desc());
        }
    }

    private static boolean helpMatches(HelpEntry e, String keyword) {
        String k = keyword.toLowerCase();
        return e.command().toLowerCase().contains(k) || e.desc().toLowerCase().contains(k);
    }
}
