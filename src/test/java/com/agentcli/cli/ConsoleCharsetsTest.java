package com.agentcli.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleCharsetsTest {

    @Test
    void usesUtf8OnNonWindowsByDefault() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertEquals(Charset.defaultCharset(), ConsoleCharsets.forConsole());
        } else {
            assertEquals(StandardCharsets.UTF_8, ConsoleCharsets.forConsole());
        }
    }
}
