package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.common.AppConfig;
import com.agentcli.llm.LlmClient;
import com.agentcli.llm.LlmClientFactory;
import com.agentcli.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * AgentCLI 程序入口。
 */
public final class Launcher {

    public static final String VERSION = "1.0.0";

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            run(args, System.out, System.err);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println("使用 --help 查看帮助。");
            System.exit(2);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws Exception {
        CliArgs cli = CliArgs.parse(args);

        if (cli.help) {
            printHelp(out);
            return;
        }
        if (cli.version) {
            out.println("AgentCLI " + VERSION);
            return;
        }

        AppConfig config = AppConfig.load();
        if (cli.provider != null && !cli.provider.isBlank()) {
            config.setDefaultProvider(CliArgs.normalizeProvider(cli.provider));
        }

        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            err.println("未找到可用的 API Key。请在 .env 或 ~/.agentcli/config.json 中配置至少一个 provider。");
            err.println("示例: GLM_API_KEY=your_key  或  DEEPSEEK_API_KEY=your_key");
            System.exit(1);
            return;
        }

        String projectPath = cli.projectPath != null && !cli.projectPath.isBlank()
                ? cli.projectPath
                : System.getProperty("user.dir");
        ToolRegistry toolRegistry = new ToolRegistry(projectPath);
        ReActAgent agent = new ReActAgent(client, toolRegistry);
        InteractiveSession session = new InteractiveSession(agent, client, config);

        if (cli.prompt != null && !cli.prompt.isBlank()) {
            session.runTurn(cli.prompt, out);
            return;
        }

        BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        session.run(input, out);
    }

    private static void printHelp(java.io.PrintStream out) {
        out.println("AgentCLI " + VERSION);
        out.println("用法: agentcli [选项] [问题...]");
        out.println();
        out.println("选项:");
        out.println("  -h, --help            显示帮助并退出");
        out.println("  -v, --version         显示版本并退出");
        out.println("  -p, --prompt TEXT     单次提问后退出");
        out.println("  --provider NAME       指定模型 provider (glm/deepseek/openai)");
        out.println("  --project, -C DIR     指定项目根目录（工具沙箱范围）");
        out.println();
        out.println("不带参数启动时进入交互式 REPL。");
        InteractiveSession.printSessionHelp(out);
    }
}
