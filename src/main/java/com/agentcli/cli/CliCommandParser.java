package com.agentcli.cli;

import java.util.List;

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
        MCP,
        PLAN,
        MEMORY_STATUS,
        MEMORY_LIST,
        MEMORY_CLEAR,
        MEMORY_SEARCH,
        MEMORY_DELETE,
        AUDIT,
        SKILL,
        LSP,
        STATUS,
        USAGE
    }

    record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    /** 补全用的规范命令目录（/mem 作为 /memory 前缀别名；含常用子命令）。 */
    static List<String> commandNames() {
        return List.of(
                "/exit", "/quit", "/clear", "/help",
                "/compact", "/hitl",
                "/mcp", "/mcp logs", "/mcp status",
                "/plan", "/model",
                "/memory", "/memory list", "/memory clear", "/memory search", "/memory delete",
                "/audit",
                "/skill", "/lsp",
                "/status", "/usage");
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
        if (trimmed.equalsIgnoreCase("/status")) {
            return new ParsedCommand(CommandType.STATUS, null);
        }
        if (trimmed.equalsIgnoreCase("/usage")) {
            return new ParsedCommand(CommandType.USAGE, null);
        }
        if (trimmed.equalsIgnoreCase("/hitl")) {
            return new ParsedCommand(CommandType.HITL, null);
        }
        if (trimmed.regionMatches(true, 0, "/hitl ", 0, 6)) {
            return new ParsedCommand(CommandType.HITL, trimmed.substring(6).trim());
        }
        if (trimmed.equalsIgnoreCase("/mcp")) {
            return new ParsedCommand(CommandType.MCP, null);
        }
        if (trimmed.regionMatches(true, 0, "/mcp ", 0, 5)) {
            return new ParsedCommand(CommandType.MCP, trimmed.substring(5).trim());
        }
        if (trimmed.equalsIgnoreCase("/plan")) {
            return new ParsedCommand(CommandType.PLAN, null);
        }
        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            return new ParsedCommand(CommandType.PLAN, trimmed.substring(6).trim());
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
        if (trimmed.equalsIgnoreCase("/audit")) {
            return new ParsedCommand(CommandType.AUDIT, null);
        }
        if (trimmed.regionMatches(true, 0, "/audit ", 0, 7)) {
            return new ParsedCommand(CommandType.AUDIT, trimmed.substring(7).trim());
        }
        if (trimmed.equalsIgnoreCase("/skill") || trimmed.equalsIgnoreCase("/skills")) {
            return new ParsedCommand(CommandType.SKILL, null);
        }
        if (trimmed.regionMatches(true, 0, "/skill ", 0, 7)) {
            return new ParsedCommand(CommandType.SKILL, trimmed.substring(7).trim());
        }
        if (trimmed.equalsIgnoreCase("/lsp")) {
            return new ParsedCommand(CommandType.LSP, null);
        }
        if (trimmed.regionMatches(true, 0, "/lsp ", 0, 5)) {
            return new ParsedCommand(CommandType.LSP, trimmed.substring(5).trim());
        }
        if (trimmed.equalsIgnoreCase("lsp")) {
            return new ParsedCommand(CommandType.LSP, null);
        }
        if (trimmed.regionMatches(true, 0, "lsp ", 0, 4)) {
            return new ParsedCommand(CommandType.LSP, trimmed.substring(4).trim());
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
