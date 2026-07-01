package com.agentcli.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherTest {

    @Test
    void helpFlagPrintsUsage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Launcher.run(new String[]{"--help"}, new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("AgentCLI"));
        assertTrue(text.contains("--prompt"));
    }

    @Test
    void versionFlagPrintsVersion() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Launcher.run(new String[]{"-v"}, new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(Launcher.VERSION));
    }
}
