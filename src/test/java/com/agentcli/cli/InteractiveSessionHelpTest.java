package com.agentcli.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveSessionHelpTest {

    private static String help(String keyword) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        InteractiveSession.printSessionHelp(out, keyword);
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fullHelpListsAllCommands() {
        String s = help(null);
        assertTrue(s.contains("/help, /?"), "应包含 /help");
        assertTrue(s.contains("/clear"), "应包含 /clear");
        assertTrue(s.contains("-p, --prompt TEXT"), "应包含启动参数");
        assertTrue(s.contains("斜杠命令:"), "应有斜杠命令分组头");
        assertTrue(s.contains("启动参数:"), "应有启动参数分组头");
    }

    @Test
    void keywordFiltersToMatchingEntries() {
        String s = help("team");
        assertTrue(s.contains("/team <目标>"), "team 关键词应命中 /team");
        assertFalse(s.contains("/clear"), "team 关键词不应命中 /clear");
        assertFalse(s.contains("清空当前对话历史"), "team 关键词不应命中 /clear 描述");
    }

    @Test
    void keywordMatchesChineseDescription() {
        String s = help("记忆");
        assertTrue(s.contains("/memory, /mem"), "记忆 应命中 /memory");
        assertTrue(s.contains("/memory search Q"), "记忆 应命中子命令");
        assertFalse(s.contains("/clear"), "记忆 不应命中 /clear");
    }

    @Test
    void keywordMatchesAlias() {
        String s = help("mem");
        assertTrue(s.contains("/memory, /mem"), "mem 应命中别名");
        assertFalse(s.contains("/clear"), "mem 不应命中无关命令");
    }

    @Test
    void noMatchShowsHint() {
        String s = help("zzzznotacommand");
        assertTrue(s.contains("没有匹配"), "无匹配应给出提示");
        assertFalse(s.contains("/clear"), "无匹配不应列出任何命令");
    }

    @Test
    void blankKeywordShowsFull() {
        String s = help("   ");
        assertTrue(s.contains("/clear"), "空白关键词应等价全量");
        assertTrue(s.contains("/team <目标>"), "空白关键词应等价全量");
    }

    @Test
    void parserCapturesHelpKeyword() {
        CliCommandParser.ParsedCommand c = CliCommandParser.parse("/help team");
        assertEquals(CliCommandParser.CommandType.HELP, c.type());
        assertEquals("team", c.payload());
        CliCommandParser.ParsedCommand c2 = CliCommandParser.parse("/help");
        assertEquals(CliCommandParser.CommandType.HELP, c2.type());
        assertNull(c2.payload());
        CliCommandParser.ParsedCommand c3 = CliCommandParser.parse("help model");
        assertEquals(CliCommandParser.CommandType.HELP, c3.type());
        assertEquals("model", c3.payload());
    }
}
