package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.common.AppConfig;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveSessionTest {

    private StubLlmClient llm;
    private ReActAgent agent;
    private InteractiveSession session;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        llm = new StubLlmClient("stub", "stub");
        ToolRegistry registry = new ToolRegistry(projectRoot.toString());
        agent = new ReActAgent(llm, registry);
        session = new InteractiveSession(agent, llm, new AppConfig());
        output = new ByteArrayOutputStream();
    }

    @Test
    void runTurnPrintsFinalAnswerWhenNotStreamed() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "done", null, List.of(), 1, 1));

        session.runTurn("hello", new PrintStream(output, true, StandardCharsets.UTF_8));

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("done"));
    }

    @Test
    void clearCommandResetsHistory() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "x", null, List.of(), 1, 1));
        session.runTurn("hi", new PrintStream(output, true, StandardCharsets.UTF_8));
        assertTrue(agent.getConversationHistory().size() > 1);

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/clear"), out));
        assertEquals(1, agent.getConversationHistory().size());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("清空"));
    }

    @Test
    void replExitsOnExitCommand() throws Exception {
        session.run(new java.io.BufferedReader(new StringReader("/exit\n")),
                new PrintStream(output, true, StandardCharsets.UTF_8));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("再见"));
    }
}
