package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.audit.AuditLog;
import com.agentcli.audit.AuditLlmClient;
import com.agentcli.common.AnsiStyle;
import com.agentcli.common.AppConfig;
import com.agentcli.hitl.HitlHandler;
import com.agentcli.hitl.HitlToolRegistry;
import com.agentcli.llm.LlmClient;
import com.agentcli.llm.LlmClientFactory;
import com.agentcli.mcp.McpServerManager;
import com.agentcli.memory.MemoryEntry;
import com.agentcli.plan.PlanExecuteAgent;
import com.agentcli.plan.ExecutionPlan;
import com.agentcli.plan.PlanStore;
import com.agentcli.plan.Task;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;
import com.agentcli.store.SqliteStore;

import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;

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

                runTurn(line, out);
            }
        } finally {
            input.close();
        }
    }

    /** 执行一轮 Agent 对话并输出到终端。 */
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
                printSessionHelp(out);
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
            case NONE -> false;
        };
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

    static void printSessionHelp(PrintStream out) {
        out.println();
        out.println("斜杠命令:");
        out.println("  /help, /?          显示本帮助");
        out.println("  /clear             清空当前对话历史");
        out.println("  /model [provider]  查看或切换模型 (glm/deepseek/openai)");
        out.println("  /memory, /mem       查看记忆状态");
        out.println("  /memory list        列出长期记忆");
        out.println("  /memory search Q    搜索长期记忆");
        out.println("  /memory delete ID   删除指定记忆");
        out.println("  /memory clear       清空长期记忆");
        out.println("  /compact            手动压缩对话历史");
        out.println("  /hitl [on|off|clear] 查看/切换/重置人工审批");
        out.println("  /plan <目标>         先规划再分步执行复杂任务");
        out.println("  /plan resume         恢复最近一个未完成的 plan 并续跑");
        out.println("  /mcp [status|logs]   MCP 服务器状态与日志");
        out.println("  /audit [数量]        查看最近的 LLM 调用与审批审计记录");
        out.println("  /skill [list]        列出已加载 skill 与启用状态");
        out.println("  /skill show <name>   查看某个 skill 的完整 SKILL.md");
        out.println("  /skill on/off <name> 启用 / 禁用某个 skill");
        out.println("  /skill reload         重新扫描 skill 目录");
        out.println("  /exit, /quit       退出交互模式");
        out.println();
        out.println("启动参数:");
        out.println("  -p, --prompt TEXT  单次提问后退出");
        out.println("  --provider NAME    指定默认模型 provider");
        out.println("  --project, -C DIR  指定项目根目录");
    }
}
