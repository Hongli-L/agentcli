package com.agentcli.cli;

/**
 * 解析交互式会话中的斜杠命令。
 */
final class CliCommandParser {

    enum CommandType {
        NONE,
        EXIT,
        CLEAR,
        HELP,
        MODEL,
        COMPACT,
        HITL,
        MEMORY_STATUS,
        MEMORY_LIST,
        MEMORY_CLEAR,
        MEMORY_SEARCH,
        MEMORY_DELETE
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
        if (trimmed.equalsIgnoreCase("/compact")) {
            return new ParsedCommand(CommandType.COMPACT, null);
        }
        if (trimmed.equalsIgnoreCase("/hitl")) {
            return new ParsedCommand(CommandType.HITL, null);
        }
        if (trimmed.regionMatches(true, 0, "/hitl ", 0, 6)) {
            return new ParsedCommand(CommandType.HITL, trimmed.substring(6).trim());
        }
        if (trimmed.equalsIgnoreCase("/model")) {
            return new ParsedCommand(CommandType.MODEL, null);
        }
        if (trimmed.regionMatches(true, 0, "/model ", 0, 7)) {
            return new ParsedCommand(CommandType.MODEL, trimmed.substring(7).trim());
        }
        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }
        if (trimmed.equalsIgnoreCase("/memory list") || trimmed.equalsIgnoreCase("/mem list")) {
            return new ParsedCommand(CommandType.MEMORY_LIST, null);
        }
        if (trimmed.equalsIgnoreCase("/memory clear") || trimmed.equalsIgnoreCase("/mem clear")) {
            return new ParsedCommand(CommandType.MEMORY_CLEAR, null);
        }
        if (trimmed.regionMatches(true, 0, "/memory search ", 0, 15)) {
            return new ParsedCommand(CommandType.MEMORY_SEARCH, trimmed.substring(15).trim());
        }
        if (trimmed.regionMatches(true, 0, "/mem search ", 0, 12)) {
            return new ParsedCommand(CommandType.MEMORY_SEARCH, trimmed.substring(12).trim());
        }
        if (trimmed.regionMatches(true, 0, "/memory delete ", 0, 15)) {
            return new ParsedCommand(CommandType.MEMORY_DELETE, trimmed.substring(15).trim());
        }
        if (trimmed.regionMatches(true, 0, "/mem delete ", 0, 12)) {
            return new ParsedCommand(CommandType.MEMORY_DELETE, trimmed.substring(12).trim());
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
