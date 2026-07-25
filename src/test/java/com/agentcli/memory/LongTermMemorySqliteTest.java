package com.agentcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LongTermMemorySqliteTest {

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        LongTermMemory a = new LongTermMemory(dir.toFile());
        a.store(new MemoryEntry("m1", "Java 17 项目", MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 3));
        assertEquals(1, a.size());

        LongTermMemory b = new LongTermMemory(dir.toFile());
        assertEquals(1, b.size());
        assertEquals("Java 17 项目", b.getAll().get(0).getContent());
    }

    @Test
    void importsLegacyJsonOnFirstRun(@TempDir Path dir) throws Exception {
        String json = "[{\"id\":\"old1\",\"content\":\"旧记忆条目\",\"type\":\"FACT\","
                + "\"timestamp\":\"2024-01-01T00:00:00Z\",\"metadata\":{\"scope\":\"global\"},"
                + "\"tokenCount\":5}]";
        Files.writeString(dir.resolve("long_term_memory.json"), json);

        LongTermMemory mem = new LongTermMemory(dir.toFile());
        assertEquals(1, mem.size());
        assertEquals("旧记忆条目", mem.getAll().get(0).getContent());
    }
}
