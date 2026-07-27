package com.agentcli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStateStoreTest {

    @TempDir
    Path tmp;

    @Test
    void disableEnableRoundtripPersistsToFile() throws Exception {
        Path file = tmp.resolve("skills.json");
        SkillStateStore store = new SkillStateStore(file);
        assertTrue(store.disabled().isEmpty());

        store.disable("a");
        store.disable("b");
        assertTrue(store.disabled().contains("a"));
        assertTrue(store.disabled().contains("b"));

        store.enable("a");
        assertFalse(store.disabled().contains("a"));
        assertTrue(store.disabled().contains("b"));

        // 新实例从文件读取
        SkillStateStore store2 = new SkillStateStore(file);
        assertFalse(store2.disabled().contains("a"));
        assertTrue(store2.disabled().contains("b"));
    }

    @Test
    void missingFileReturnsEmpty() {
        SkillStateStore store = new SkillStateStore(tmp.resolve("missing.json"));
        assertTrue(store.disabled().isEmpty());
    }

    @Test
    void corruptFileReturnsEmpty() throws Exception {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{ this is not valid json ");
        SkillStateStore store = new SkillStateStore(file);
        assertTrue(store.disabled().isEmpty());
    }

    @Test
    void emptyFileReturnsEmpty() throws Exception {
        Path file = tmp.resolve("empty.json");
        Files.writeString(file, "");
        SkillStateStore store = new SkillStateStore(file);
        assertTrue(store.disabled() instanceof Set<?>);
        assertTrue(store.disabled().isEmpty());
    }
}
