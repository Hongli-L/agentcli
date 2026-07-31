package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.common.AppConfig;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void statusCommandPrintsContextProfile() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/status"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("会话状态"), text);
        assertTrue(text.contains("stub"), text);
    }

    @Test
    void usageCommandPrintsTokenReport() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "ok", null, List.of(), 120, 30));
        session.runTurn("hi", new PrintStream(output, true, StandardCharsets.UTF_8));
        output.reset();

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/usage"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Token 用量"), text);
        assertTrue(text.contains("估算"), text);
    }

    @Test
    void sessionHelpMentionsStatusAndUsage() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        InteractiveSession.printSessionHelp(out);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("/status"), text);
        assertTrue(text.contains("/usage"), text);
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

    @Test
    void skillCommandsRouteCorrectly(@TempDir Path projectRoot) throws Exception {
        Path skillsDir = projectRoot.resolve(".agentcli").resolve("skills");
        writeSkill(skillsDir, "alpha", "alpha desc");
        writeSkill(skillsDir, "beta", "beta desc");
        SkillStateStore store = new SkillStateStore(projectRoot.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, skillsDir, store);
        registry.reload();

        ReActAgent agent2 = new ReActAgent(llm, new ToolRegistry(projectRoot.toString()));
        InteractiveSession skillSession = new InteractiveSession(agent2, llm, new AppConfig(),
                null, registry, store);

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        skillSession.handleCommand(CliCommandParser.parse("/skill"), out);
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("alpha"), text);
        assertTrue(text.contains("beta"), text);

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill show alpha"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("body of alpha"));

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill off alpha"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("已禁用"));
        assertNull(registry.findSkill("alpha"));

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill on alpha"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("已启用"));
        assertNotNull(registry.findSkill("alpha"));

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill reload"), out);
        String reloaded = output.toString(StandardCharsets.UTF_8);
        assertTrue(reloaded.contains("alpha"), reloaded);
    }

    @Test
    void skillCommandWithoutRegistryPrintsUninitialized() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        session.handleCommand(CliCommandParser.parse("/skill"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("未初始化"));
    }

    private void writeSkill(Path root, String name, String description) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\nbody of " + name + "\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
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
