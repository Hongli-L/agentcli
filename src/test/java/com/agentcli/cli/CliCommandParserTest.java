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
    void plainTextIsNotCommand() {
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("explain this code").type());
    }
}
