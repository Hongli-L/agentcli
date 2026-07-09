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
    void runTurnDoesNotDuplicateStreamedAnswer() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "done", null, List.of(), 1, 1));

        session.runTurn("hello", new PrintStream(output, true, StandardCharsets.UTF_8));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(text, "done"));
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
    void memoryStatusCommandPrintsStatus() {
        agent.getMemoryManager().storeFact("test memory");
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/memory"), out));
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("长期记忆"));
    }

    @Test
    void replExitsOnExitCommand() throws Exception {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        ReplReader input = new ReplReader.BufferedReplReader(
                new java.io.BufferedReader(new StringReader("/exit\n")),
                out);
        session.run(input, out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("再见"));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
