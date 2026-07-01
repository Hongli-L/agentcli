package com.agentcli.cli;

/**
 * 解析交互式会话中的斜杠命令。
 *
 * 以 {@code /} 开头的输入视为命令；普通文本交给 Agent 处理。
 */
final class CliCommandParser {

    enum CommandType {
        NONE,
        EXIT,
        CLEAR,
        HELP,
        MODEL
    }

    record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        if (isExit(trimmed)) {
            return new ParsedCommand(CommandType.EXIT, null);
        }
        if (trimmed.equalsIgnoreCase("/clear") || trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }
        if (trimmed.equalsIgnoreCase("/help") || trimmed.equalsIgnoreCase("help")
                || trimmed.equals("?") || trimmed.equals("/?")) {
            return new ParsedCommand(CommandType.HELP, null);
        }
        if (trimmed.equalsIgnoreCase("/model")) {
            return new ParsedCommand(CommandType.MODEL, null);
        }
        if (trimmed.regionMatches(true, 0, "/model ", 0, 7)) {
            return new ParsedCommand(CommandType.MODEL, trimmed.substring(7).trim());
        }
        return ParsedCommand.none();
    }

    private static boolean isExit(String trimmed) {
        return trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit")
                || trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit");
    }
}
