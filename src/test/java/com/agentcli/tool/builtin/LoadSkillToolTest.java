package com.agentcli.tool.builtin;

import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSkillToolTest {

    @TempDir
    Path tmp;

    @Test
    void returnsBodyAndReferencesForExistingSkill() throws Exception {
        Path skillsDir = tmp.resolve("skills");
        Path skillDir = skillsDir.resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: demo skill\n---\nThis is the body.\n");

        Path refs = skillDir.resolve("references");
        Files.createDirectories(refs);
        Files.writeString(refs.resolve("ref1.md"), "reference content");

        SkillStateStore store = new SkillStateStore(tmp.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(skillsDir, null, store);
        registry.reload();

        LoadSkillTool tool = new LoadSkillTool(registry);
        assertEquals("load_skill", tool.name());

        String out = tool.execute(Map.of("name", "demo"));
        assertTrue(out.contains("## 已加载 Skill: demo"), out);
        assertTrue(out.contains("This is the body."), out);
        assertTrue(out.contains("ref1.md"), out);
    }

    @Test
    void notFoundReturnsError() {
        SkillRegistry registry = new SkillRegistry(tmp.resolve("none"), null,
                new SkillStateStore(tmp.resolve("skills.json")));
        registry.reload();
        LoadSkillTool tool = new LoadSkillTool(registry);
        String out = tool.execute(Map.of("name", "ghost"));
        assertTrue(out.contains("Skill 未找到"), out);
    }

    @Test
    void disabledSkillReportsDisabled() throws Exception {
        Path skillsDir = tmp.resolve("skills");
        Path skillDir = skillsDir.resolve("off");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: off\ndescription: off skill\n---\nbody\n");
        Path stateFile = tmp.resolve("skills.json");
        Files.writeString(stateFile, "{ \"disabled\": [ \"off\" ] }");
        SkillRegistry registry = new SkillRegistry(skillsDir, null, new SkillStateStore(stateFile));
        registry.reload();

        LoadSkillTool tool = new LoadSkillTool(registry);
        String out = tool.execute(Map.of("name", "off"));
        assertTrue(out.contains("Skill 已禁用"), out);
    }

    @Test
    void missingNameReturnsError() {
        LoadSkillTool tool = new LoadSkillTool(new SkillRegistry(null, null,
                new SkillStateStore(tmp.resolve("skills.json"))));
        String out = tool.execute(Map.of());
        assertTrue(out.contains("参数错误"), out);
    }

    @Test
    void markdownBodyAndSpecialCharactersAreReturnedSafely() throws Exception {
        Path skillsDir = tmp.resolve("skills");
        Path skillDir = skillsDir.resolve("md");
        Files.createDirectories(skillDir);
        String body = "Use `code`, **bold**, and a fenced block:\n```java\nint x = 1;\n```\n";
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: md\ndescription: markdown skill\n---\n" + body);

        SkillRegistry registry = new SkillRegistry(skillsDir, null,
                new SkillStateStore(tmp.resolve("skills.json")));
        registry.reload();

        LoadSkillTool tool = new LoadSkillTool(registry);
        String out = tool.execute(Map.of("name", "md"));
        assertTrue(out.contains("## 已加载 Skill: md"), out);
        assertTrue(out.contains("```java"), out);
        assertTrue(out.contains("int x = 1"), out);
        assertTrue(out.contains("**bold**"), out);
    }
}
