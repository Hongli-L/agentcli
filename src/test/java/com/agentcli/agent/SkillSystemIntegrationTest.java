package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立集成校验：证明 ReActAgent 真的把 SkillRegistry 的索引段拼进 system prompt，
 * 而非仅存了字段。覆盖 updateSystemPrompt(每次 run) 与 resetHistory(clearHistory) 两条路径。
 */
class SkillSystemIntegrationTest {

    @Test
    void systemPromptIncludesSkillIndexAfterRegistrySet(@TempDir Path projectRoot) throws Exception {
        System.setProperty("AGENTCLI_MEMORY_DIR", projectRoot.resolve(".memory-test").toString());

        Path skillsDir = projectRoot.resolve(".agentcli").resolve("skills");
        Path demoDir = skillsDir.resolve("demo");
        Files.createDirectories(demoDir);
        Files.writeString(demoDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: demo skill for QA\n---\nBody of demo skill.\n");

        SkillStateStore store = new SkillStateStore(projectRoot.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, skillsDir, store);
        registry.reload();

        StubLlmClient llm = new StubLlmClient("stub", "stub");
        ReActAgent agent = new ReActAgent(llm, new ToolRegistry(projectRoot.toString()));

        // 注入前：system prompt 不应含 skill 索引段
        String before = agent.getConversationHistory().get(0).content();
        assertFalse(before.contains("## 可用 Skills"), "注入前不应含 skill 索引");

        agent.setSkillRegistry(registry);

        llm.enqueue(new LlmClient.ChatResponse("assistant", "ok", null, List.of(), 1, 1));
        agent.run("use the demo skill");

        String system = agent.getConversationHistory().get(0).content();
        assertTrue(system.contains("## 可用 Skills"), system);
        assertTrue(system.contains("- **demo**：demo skill for QA"), system);
    }

    @Test
    void resetHistoryAlsoInjectsSkillIndex(@TempDir Path projectRoot) throws Exception {
        System.setProperty("AGENTCLI_MEMORY_DIR", projectRoot.resolve(".memory-test").toString());

        Path skillsDir = projectRoot.resolve(".agentcli").resolve("skills");
        Path demoDir = skillsDir.resolve("demo");
        Files.createDirectories(demoDir);
        Files.writeString(demoDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: demo skill for QA\n---\nbody\n");

        SkillRegistry registry = new SkillRegistry(null, skillsDir,
                new SkillStateStore(projectRoot.resolve("skills.json")));
        registry.reload();

        StubLlmClient llm = new StubLlmClient("stub", "stub");
        ReActAgent agent = new ReActAgent(llm, new ToolRegistry(projectRoot.toString()));
        agent.setSkillRegistry(registry);

        llm.enqueue(new LlmClient.ChatResponse("assistant", "first", null, List.of(), 1, 1));
        agent.run("one");

        // 清空历史会触发 resetHistory -> buildSystemPrompt，也应含 skill 索引
        agent.clearHistory();
        String systemAfterReset = agent.getConversationHistory().get(0).content();
        assertTrue(systemAfterReset.contains("## 可用 Skills"), systemAfterReset);
        assertTrue(systemAfterReset.toLowerCase(Locale.ROOT).contains("demo"), systemAfterReset);
    }
}
