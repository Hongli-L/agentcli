package com.agentcli.memory;

import com.agentcli.testsupport.StubLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        LongTermMemory storage = new LongTermMemory(tempDir.toFile());
        TokenBudget budget = new TokenBudget(8_000);
        manager = new MemoryManager(storage, budget);
        manager.setProjectPath(tempDir.toString());
    }

    @Test
    void storeAndListProjectScopedFacts() {
        manager.storeFact("prefer Java 17");
        var entries = manager.listLongTerm();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).getContent().contains("Java 17"));
    }

    @Test
    void searchFindsMatchingFact() {
        manager.storeFact("database is PostgreSQL");
        manager.storeFact("UI uses React");
        var hits = manager.searchLongTerm("PostgreSQL", 5);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getContent().contains("PostgreSQL"));
    }

    @Test
    void globalFactVisibleAcrossProjects() {
        manager.storeFact("name is Alice", "global");
        manager.setProjectPath("/other/project");
        assertEquals(1, manager.listLongTerm().size());
    }

    @Test
    void projectFactHiddenFromOtherProjects() {
        manager.storeFact("repo uses Maven");
        manager.setProjectPath("/other/project");
        assertTrue(manager.listLongTerm().isEmpty());
    }

    @Test
    void deleteAndClear() {
        manager.storeFact("temp fact");
        String id = manager.listLongTerm().get(0).getId();
        assertTrue(manager.deleteLongTerm(id));
        assertTrue(manager.listLongTerm().isEmpty());

        manager.storeFact("again");
        manager.clearLongTerm();
        assertTrue(manager.listLongTerm().isEmpty());
    }

    @Test
    void buildContextIncludesRelevantMemory() {
        manager.storeFact("deployment target is Kubernetes");
        String context = manager.buildContextForQuery("how do we deploy?");
        assertTrue(context.contains("Kubernetes"));
        assertTrue(context.contains("相关长期记忆"));
    }

    @Test
    void duplicateFactsAreIgnored() {
        manager.storeFact("same");
        manager.storeFact("same");
        assertEquals(1, manager.getLongTermMemory().size());
    }
}
