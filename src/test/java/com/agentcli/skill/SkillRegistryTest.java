package com.agentcli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @TempDir
    Path tmp;

    private SkillRegistry registryWith(Path userDir, Path projectDir) {
        SkillStateStore store = new SkillStateStore(tmp.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(userDir, projectDir, store);
        registry.reload();
        return registry;
    }

    @Test
    void scansUserAndProjectDirectories() throws Exception {
        Path userDir = tmp.resolve("user");
        writeSkill(userDir, "alpha", "alpha desc");
        Path projDir = tmp.resolve("proj");
        writeSkill(projDir, "beta", "beta desc");

        SkillRegistry registry = registryWith(userDir, projDir);
        assertEquals(2, registry.allSkills().size());
        assertNotNull(registry.findAnySkill("alpha"));
        assertNotNull(registry.findAnySkill("beta"));
        assertNotNull(registry.findSkill("alpha"));
        assertNotNull(registry.findSkill("beta"));
    }

    @Test
    void projectOverridesUserOnSameName() throws Exception {
        Path userDir = tmp.resolve("user");
        writeSkill(userDir, "same", "user version");
        Path projDir = tmp.resolve("proj");
        writeSkill(projDir, "same", "project version");

        SkillRegistry registry = registryWith(userDir, projDir);
        assertEquals(1, registry.allSkills().size());
        Skill skill = registry.findAnySkill("same");
        assertNotNull(skill);
        assertEquals("project version", skill.description());
        assertEquals(Skill.Source.PROJECT, skill.source());
    }

    @Test
    void disabledSkillsAreExcludedFromFindSkill() throws Exception {
        Path projDir = tmp.resolve("proj");
        writeSkill(projDir, "keep", "keep me");
        writeSkill(projDir, "skip", "skip me");

        Path stateFile = tmp.resolve("skills.json");
        Files.writeString(stateFile, "{ \"disabled\": [ \"skip\" ] }");
        SkillStateStore store = new SkillStateStore(stateFile);
        SkillRegistry registry = new SkillRegistry(null, projDir, store);
        registry.reload();

        // findSkill 排除 disabled
        assertNull(registry.findSkill("skip"));
        assertNotNull(registry.findSkill("keep"));
        // findAnySkill 含 disabled
        assertNotNull(registry.findAnySkill("skip"));
        assertEquals(1, registry.enabledSkills().size());
    }

    @Test
    void missingDirectoriesAreIgnored() {
        SkillRegistry registry = registryWith(null, tmp.resolve("does-not-exist"));
        assertTrue(registry.allSkills().isEmpty());
        assertTrue(registry.warnings().isEmpty());
    }

    private void writeSkill(Path root, String name, String description) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\nbody of " + name + "\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
    }
}
