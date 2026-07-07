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
    void plainTextIsNotCommand() {
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("explain this code").type());
    }
}
