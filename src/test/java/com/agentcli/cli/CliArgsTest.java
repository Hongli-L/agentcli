package com.agentcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest {

    @Test
    void parsesFlags() {
        CliArgs args = CliArgs.parse(new String[]{"--help"});
        assertTrue(args.help);
        assertFalse(args.version);

        args = CliArgs.parse(new String[]{"-v"});
        assertTrue(args.version);
    }

    @Test
    void parsesPromptOption() {
        CliArgs args = CliArgs.parse(new String[]{"-p", "hello world"});
        assertEquals("hello world", args.prompt);
    }

    @Test
    void joinsPositionalPrompt() {
        CliArgs args = CliArgs.parse(new String[]{"explain", "this", "file"});
        assertEquals("explain this file", args.prompt);
    }

    @Test
    void parsesProviderAndProject() {
        CliArgs args = CliArgs.parse(new String[]{"--provider", "glm", "-C", "/tmp/proj"});
        assertEquals("glm", args.provider);
        assertEquals("/tmp/proj", args.projectPath);
        assertNull(args.prompt);
    }
}
