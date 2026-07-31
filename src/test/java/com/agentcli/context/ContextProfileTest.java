package com.agentcli.context;

import com.agentcli.memory.LongTermMemory;
import com.agentcli.memory.MemoryManager;
import com.agentcli.memory.TokenBudget;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextProfileTest {

    /**
     * 构造隔离的记忆管理器。
     *
     * <p>必须用独立存储目录：默认 {@code LongTermMemory} 落在共享库上，
     * 且 {@code storeFact} 会对重复内容去重，跑第二遍时计数断言就会漂移。
     */
    private MemoryManager memoryManager(Path storageDir, int contextWindow) {
        return new MemoryManager(new LongTermMemory(storageDir.resolve("memory").toFile()),
                new TokenBudget(contextWindow));
    }

    @Test
    void captureCollectsCoreFields(@TempDir Path projectRoot) {
        StubLlmClient llm = new StubLlmClient("stub-model", "deepseek");
        ToolRegistry registry = new ToolRegistry(projectRoot.toString());
        MemoryManager memory = memoryManager(projectRoot, 100_000);
        memory.getTokenBudget().recordUsage(1_000, 200, 300);

        ContextProfile profile = ContextProfile.capture(llm, registry, null, memory);

        assertEquals("deepseek", profile.provider());
        assertEquals("stub-model", profile.model());
        assertEquals(projectRoot.toString(), profile.projectPath());
        assertEquals(registry.getToolDefinitions().size(), profile.toolCount());
        assertEquals(1, profile.llmCallCount());
        assertEquals(1_000, profile.usedInputTokens());
        assertEquals(200, profile.usedOutputTokens());
        assertEquals(300, profile.cachedInputTokens());
        assertEquals(100_000, profile.contextWindow());
        assertEquals(1_200, profile.totalUsedTokens());
    }

    @Test
    void captureTreatsNullMcpManagerAsZeroServers(@TempDir Path projectRoot) {
        ContextProfile profile = ContextProfile.capture(
                new StubLlmClient("m", "glm"), new ToolRegistry(projectRoot.toString()), null,
                memoryManager(projectRoot, 1000));

        assertEquals(0, profile.mcpServerCount());
    }

    @Test
    void captureToleratesAllNullSources() {
        ContextProfile profile = ContextProfile.capture(null, null, null, null);

        assertEquals("unknown", profile.provider());
        assertEquals("unknown", profile.model());
        assertEquals(".", profile.projectPath());
        assertEquals(0, profile.toolCount());
        assertEquals(0, profile.memoryEntryCount());
        assertEquals(0, profile.totalUsedTokens());
        // 窗口不能为 0，否则占用率计算会退化
        assertTrue(profile.contextWindow() > 0);
    }

    @Test
    void captureCountsLongTermMemoryEntries(@TempDir Path projectRoot) {
        MemoryManager memory = memoryManager(projectRoot, 50_000);
        assertEquals(0, memory.listLongTerm().size(), "隔离存储的初始记忆应为空");
        memory.storeFact("用户偏好 Java 17");
        memory.storeFact("项目使用 Maven 构建");

        ContextProfile profile = ContextProfile.capture(
                new StubLlmClient("m", "glm"), new ToolRegistry(projectRoot.toString()), null, memory);

        assertEquals(2, profile.memoryEntryCount());
    }

    @Test
    void windowUsageRatioComputesFraction() {
        ContextProfile profile = new ContextProfile("glm", "m", ".", 0, 0, 0, 2,
                6_000, 2_000, 0, 40_000);

        assertEquals(0.2, profile.windowUsageRatio(), 1e-9);
    }

    @Test
    void windowUsageRatioIsClampedAndSafe() {
        ContextProfile over = new ContextProfile("glm", "m", ".", 0, 0, 0, 1,
                90_000, 90_000, 0, 100_000);
        assertEquals(1.0, over.windowUsageRatio(), 1e-9);

        ContextProfile zeroWindow = new ContextProfile("glm", "m", ".", 0, 0, 0, 0,
                10, 10, 0, 0);
        assertEquals(0.0, zeroWindow.windowUsageRatio(), 1e-9);
    }

    @Test
    void toTemplateVariablesExposesOnlyStableFields() {
        ContextProfile profile = new ContextProfile("glm", "glm-4.6", "/tmp/proj", 12, 3, 4, 5,
                6_000, 2_000, 500, 40_000);

        Map<String, String> vars = profile.toTemplateVariables();

        assertEquals("12", vars.get("tool_count"));
        assertEquals("/tmp/proj", vars.get("project_path"));
        assertEquals("glm-4.6", vars.get("model_name"));
        assertEquals(3, vars.size());
        // 红线：高频 token 数值不得进入系统提示变量表
        assertFalse(vars.containsKey("used_input_tokens"));
        assertFalse(vars.containsKey("total_used_tokens"));
    }

    @Test
    void renderContainsKeyRows() {
        ContextProfile profile = new ContextProfile("glm", "glm-4.6", "/tmp/proj", 12, 3, 4, 5,
                6_000, 2_000, 500, 40_000);

        String text = profile.render();

        assertTrue(text.contains("会话状态"), text);
        assertTrue(text.contains("glm-4.6"), text);
        assertTrue(text.contains("/tmp/proj"), text);
        assertTrue(text.contains("12"), text);
    }
}
