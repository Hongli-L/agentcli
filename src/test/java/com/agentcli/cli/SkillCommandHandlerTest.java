package com.agentcli.cli;

import com.agentcli.skill.Skill;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCommandHandlerTest {

    @TempDir
    Path tmp;

    private SkillRegistry registryWithTwoSkills() throws Exception {
        Path dir = tmp.resolve("skills");
        writeSkill(dir, "alpha", "alpha desc");
        writeSkill(dir, "beta", "beta desc");
        SkillStateStore store = new SkillStateStore(tmp.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(dir, null, store);
        registry.reload();
        return registry;
    }

    @Test
    void listShowsAllSkills() throws Exception {
        SkillRegistry registry = registryWithTwoSkills();
        String list = SkillCommandHandler.list(registry);
        assertTrue(list.contains("alpha"), list);
        assertTrue(list.contains("beta"), list);
    }

    @Test
    void startupSummaryReportsCounts() throws Exception {
        SkillRegistry registry = registryWithTwoSkills();
        String summary = SkillCommandHandler.startupSummary(registry);
        assertTrue(summary.contains("2/2"), summary);
    }

    @Test
    void showPrintsBody() throws Exception {
        SkillRegistry registry = registryWithTwoSkills();
        String show = SkillCommandHandler.show(registry, "alpha");
        assertTrue(show.contains("body of alpha"), show);
    }

    @Test
    void showWithoutNameReturnsHint() throws Exception {
        SkillRegistry registry = registryWithTwoSkills();
        assertTrue(SkillCommandHandler.show(registry, "").contains("请提供"));
    }

    @Test
    void disableThenEnableTogglesState() throws Exception {
        SkillRegistry registry = registryWithTwoSkills();
        SkillStateStore store = registry.stateStore();
        assertNotNull(registry.findSkill("alpha"));

        String disabled = SkillCommandHandler.disable(registry, store, "alpha");
        assertTrue(disabled.contains("已禁用"), disabled);
        assertNull(registry.findSkill("alpha"));
        assertNotNull(registry.findAnySkill("alpha"));

        String enabled = SkillCommandHandler.enable(registry, store, "alpha");
        assertTrue(enabled.contains("已启用"), enabled);
        assertNotNull(registry.findSkill("alpha"));
    }

    @Test
    void enableUnknownSkillReturnsError() throws Exception {
        SkillRegistry registry = registryWithTwoSkills();
        assertTrue(SkillCommandHandler.enable(registry, registry.stateStore(), "nope").contains("未找到"));
    }

    private void writeSkill(Path root, String name, String description) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\nbody of " + name + "\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
    }
}
