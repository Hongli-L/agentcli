package com.agentcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCommandParserTest {

    @Test
    void exitVariants() {
        assertEquals(CliCommandParser.CommandType.EXIT, CliCommandParser.parse("/exit").type());
        assertEquals(CliCommandParser.CommandType.EXIT, CliCommandParser.parse("quit").type());
    }

    @Test
    void clearAndHelp() {
        assertEquals(CliCommandParser.CommandType.CLEAR, CliCommandParser.parse("/clear").type());
        assertEquals(CliCommandParser.CommandType.HELP, CliCommandParser.parse("/help").type());
        assertEquals(CliCommandParser.CommandType.HELP, CliCommandParser.parse("?").type());
    }

    @Test
    void modelWithPayload() {
        var cmd = CliCommandParser.parse("/model deepseek");
        assertEquals(CliCommandParser.CommandType.MODEL, cmd.type());
        assertEquals("deepseek", cmd.payload());
    }

    @Test
    void memoryCommands() {
        assertEquals(CliCommandParser.CommandType.MEMORY_STATUS, CliCommandParser.parse("/memory").type());
        assertEquals(CliCommandParser.CommandType.MEMORY_LIST, CliCommandParser.parse("/mem list").type());
        assertEquals(CliCommandParser.CommandType.MEMORY_CLEAR, CliCommandParser.parse("/memory clear").type());
        assertEquals(CliCommandParser.CommandType.COMPACT, CliCommandParser.parse("/compact").type());

        var search = CliCommandParser.parse("/memory search deploy");
        assertEquals(CliCommandParser.CommandType.MEMORY_SEARCH, search.type());
        assertEquals("deploy", search.payload());

        var delete = CliCommandParser.parse("/mem delete fact-abc");
        assertEquals(CliCommandParser.CommandType.MEMORY_DELETE, delete.type());
        assertEquals("fact-abc", delete.payload());
    }

    @Test
    void statusAndUsageCommands() {
        assertEquals(CliCommandParser.CommandType.STATUS, CliCommandParser.parse("/status").type());
        assertEquals(CliCommandParser.CommandType.USAGE, CliCommandParser.parse("/usage").type());
    }

    @Test
    void statusAndUsageAreCaseInsensitiveAndTrimmed() {
        assertEquals(CliCommandParser.CommandType.STATUS, CliCommandParser.parse("/STATUS").type());
        assertEquals(CliCommandParser.CommandType.STATUS, CliCommandParser.parse("  /Status  ").type());
        assertEquals(CliCommandParser.CommandType.USAGE, CliCommandParser.parse("/UsAgE").type());
        assertNull(CliCommandParser.parse("/status").payload());
    }

    @Test
    void statusAndUsageAreOfferedInCompletion() {
        assertTrue(CliCommandParser.commandNames().contains("/status"));
        assertTrue(CliCommandParser.commandNames().contains("/usage"));
    }

    @Test
    void bareStatusWordIsNotACommand() {
        // 只认斜杠形式，避免把用户的自然语言 "status" 吞掉
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("status").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("usage").type());
    }

    @Test
    void plainTextIsNotCommand() {
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("explain this code").type());
    }

    // ---------------------------------------------------------------- v0.18.0 RAG 三命令

    @Test
    void ragCommandsParseTypeAndPayload() {
        var index = CliCommandParser.parse("/index");
        assertEquals(CliCommandParser.CommandType.INDEX, index.type());
        assertNull(index.payload());

        var indexWithPath = CliCommandParser.parse("/index /tmp/x");
        assertEquals(CliCommandParser.CommandType.INDEX, indexWithPath.type());
        assertEquals("/tmp/x", indexWithPath.payload());

        var search = CliCommandParser.parse("/search 用户登录");
        assertEquals(CliCommandParser.CommandType.SEARCH, search.type());
        assertEquals("用户登录", search.payload());

        var graph = CliCommandParser.parse("/graph ReactAgent");
        assertEquals(CliCommandParser.CommandType.GRAPH, graph.type());
        assertEquals("ReactAgent", graph.payload());
    }

    @Test
    void ragCommandsWithoutArgumentsStillReturnTheirType() {
        // 无参也必须被识别为命令，否则 "/search" 会掉进自然语言轮被当成提问发给模型
        assertEquals(CliCommandParser.CommandType.SEARCH, CliCommandParser.parse("/search").type());
        assertNull(CliCommandParser.parse("/search").payload());
        assertEquals(CliCommandParser.CommandType.GRAPH, CliCommandParser.parse("/graph").type());
        assertNull(CliCommandParser.parse("/graph").payload());
    }

    @Test
    void ragCommandsAreCaseInsensitiveAndTrimmed() {
        assertEquals(CliCommandParser.CommandType.INDEX, CliCommandParser.parse("  /INDEX  ").type());
        assertEquals(CliCommandParser.CommandType.SEARCH, CliCommandParser.parse("/SeArCh 登录").type());
        assertEquals("登录", CliCommandParser.parse("/SeArCh   登录  ").payload());
        assertEquals(CliCommandParser.CommandType.GRAPH, CliCommandParser.parse("/Graph Foo").type());
    }

    @Test
    void ragCommandPrefixesDoNotSwallowSimilarWords() {
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("/searchxyz").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("/indexing").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("/graphql").type());
        // 只认斜杠形式，避免吞掉自然语言
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("search 用户登录").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("graph ReactAgent").type());
    }

    @Test
    void ragCommandsAreOfferedInCompletion() {
        assertTrue(CliCommandParser.commandNames().contains("/index"));
        assertTrue(CliCommandParser.commandNames().contains("/search"));
        assertTrue(CliCommandParser.commandNames().contains("/graph"));
    }

    // ---------------------------------------------------------------- v0.20.0 多 Agent 三命令

    @Test
    void orchestrationCommandsAreParsedWithTrimmedPayload() {
        var team = CliCommandParser.parse("/team 分析项目结构并总结三个改进点");
        assertEquals(CliCommandParser.CommandType.TEAM, team.type());
        assertEquals("分析项目结构并总结三个改进点", team.payload());

        var cancel = CliCommandParser.parse("/cancel   task_2  ");
        assertEquals(CliCommandParser.CommandType.CANCEL, cancel.type());
        assertEquals("task_2", cancel.payload());

        var tasks = CliCommandParser.parse("  /TASKS  ");
        assertEquals(CliCommandParser.CommandType.TASKS, tasks.type());
        assertNull(tasks.payload());
    }

    @Test
    void orchestrationCommandsWithoutArgumentsStillReturnTheirType() {
        // 无参也必须被识别为命令，否则 "/team" 会掉进自然语言轮被当成提问发给模型
        assertEquals(CliCommandParser.CommandType.TEAM, CliCommandParser.parse("/team").type());
        assertNull(CliCommandParser.parse("/team").payload());
        assertEquals(CliCommandParser.CommandType.CANCEL, CliCommandParser.parse("/CANCEL").type());
        assertNull(CliCommandParser.parse("/cancel").payload());

        // 前缀不得吞掉相似词
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("/teamwork").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("/cancellation").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("/taskset").type());
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("team 分析项目").type());

        assertTrue(CliCommandParser.commandNames().contains("/team"));
        assertTrue(CliCommandParser.commandNames().contains("/cancel"));
        assertTrue(CliCommandParser.commandNames().contains("/tasks"));
    }
}
