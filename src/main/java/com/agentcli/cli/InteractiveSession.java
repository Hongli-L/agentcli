package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.common.AppConfig;
import com.agentcli.llm.LlmClient;
import com.agentcli.llm.LlmClientFactory;

import java.io.BufferedReader;
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

    public void run(BufferedReader input, PrintStream out) throws IOException {
        printWelcome(out);
        while (true) {
            out.print("\nYou> ");
            out.flush();
            String line = input.readLine();
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
    }

    /** 执行一轮 Agent 对话并输出到终端。 */
    public void runTurn(String userInput, PrintStream out) {
        StreamingConsoleListener listener = new StreamingConsoleListener(out);
        agent.setListener(listener);
        String response = agent.run(userInput);
        listener.finishTurn();
        if (!listener.hasStreamedContent() && response != null && !response.isBlank()) {
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
            case NONE -> false;
        };
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
        out.println("AgentCLI v" + Launcher.VERSION + "  交互模式");
        out.println("模型: " + llmClient.getProviderName() + " / " + llmClient.getModelName());
        out.println("项目: " + agent.getToolRegistry().getProjectPath());
        out.println("输入 /help 查看命令，/exit 退出。");
    }

    static void printSessionHelp(PrintStream out) {
        out.println();
        out.println("斜杠命令:");
        out.println("  /help, /?          显示本帮助");
        out.println("  /clear             清空当前对话历史");
        out.println("  /model [provider]  查看或切换模型 (glm/deepseek/openai)");
        out.println("  /exit, /quit       退出交互模式");
        out.println();
        out.println("启动参数:");
        out.println("  -p, --prompt TEXT  单次提问后退出");
        out.println("  --provider NAME    指定默认模型 provider");
        out.println("  --project, -C DIR  指定项目根目录");
    }
}
