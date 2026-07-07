package com.agentcli.tool.builtin;

import com.agentcli.memory.LongTermMemory;
import com.agentcli.memory.MemoryManager;
import com.agentcli.memory.TokenBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveMemoryToolTest {

    @TempDir
    Path tempDir;

    private SaveMemoryTool tool;
    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new MemoryManager(new LongTermMemory(tempDir.toFile()), new TokenBudget(8_000));
        manager.setProjectPath(tempDir.toString());
        tool = new SaveMemoryTool(manager);
    }

    @Test
    void savesProjectScopedFact() {
        String result = tool.execute(java.util.Map.of("fact", "use Lombok sparingly"));
        assertTrue(result.contains("已保存"));
        assertEquals(1, manager.listLongTerm().size());
    }

    @Test
    void rejectsEmptyFact() {
        String result = tool.execute(java.util.Map.of("fact", "  "));
        assertTrue(result.contains("fact 不能为空"));
    }
}
