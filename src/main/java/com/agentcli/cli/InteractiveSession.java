package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.common.AnsiStyle;
import com.agentcli.common.AppConfig;
import com.agentcli.llm.LlmClient;
import com.agentcli.llm.LlmClientFactory;
import com.agentcli.memory.MemoryEntry;

import java.io.IOException;
import java.io.PrintStream;

/**
 * 交互式 REPL：读取用户输入，分发斜杠命令或交给 {@link ReActAgent} 处理。
 */
public class InteractiveSession {

    private final ReActAgent agent;
    private final AppConfig config;
    private LlmClient llmClient;

    public InteractiveSession(ReActAgent agent, LlmClient llmClient, AppConfig config) {
        this.agent = agent;
        this.llmClient = llmClient;
        this.config = config;
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
        llmClient = next;
        agent.setLlmClient(next);
        config.setDefaultProvider(normalized);
        config.save();
        out.println("已切换: " + next.getProviderName() + " / " + next.getModelName());
    }

    private void printWelcome(PrintStream out) {
        out.println();
        out.println(AnsiStyle.heading("AgentCLI v" + Launcher.VERSION));
        out.println(AnsiStyle.subtle("模型: " + llmClient.getProviderName() + " / " + llmClient.getModelName()
                + "  ·  项目: " + agent.getToolRegistry().getProjectPath()));
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
        out.println("  /exit, /quit       退出交互模式");
        out.println();
        out.println("启动参数:");
        out.println("  -p, --prompt TEXT  单次提问后退出");
        out.println("  --provider NAME    指定默认模型 provider");
        out.println("  --project, -C DIR  指定项目根目录");
    }
}
