package com.agentcli.cli;

import com.agentcli.common.AppConfig;

/**
 * AgentCLI 程序入口。
 *
 * C1 阶段仅提供启动骨架：打印 banner、版本与当前默认 provider。
 * ReAct 调度循环、工具框架、多模型接入与交互式会话将在后续批次接入。
 */
public final class Launcher {

    public static final String VERSION = "1.0.0";

    private Launcher() {
    }

    public static void main(String[] args) {
        if (hasFlag(args, "--version", "-v")) {
            System.out.println("AgentCLI " + VERSION);
            return;
        }
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        printBanner();
    }

    private static void printBanner() {
        AppConfig config = AppConfig.load();
        System.out.println();
        System.out.println("  AgentCLI v" + VERSION);
        System.out.println("  A lightweight ReAct-based command-line AI agent");
        System.out.println("  default provider: " + config.getDefaultProvider());
        System.out.println();
        System.out.println("  交互式会话将在后续版本接入。当前可用：--version / --help");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("AgentCLI " + VERSION);
        System.out.println("用法: java -jar agentcli-" + VERSION + ".jar [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  -v, --version   显示版本并退出");
        System.out.println("  -h, --help      显示帮助并退出");
    }

    private static boolean hasFlag(String[] args, String... flags) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            for (String flag : flags) {
                if (flag.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }
}
