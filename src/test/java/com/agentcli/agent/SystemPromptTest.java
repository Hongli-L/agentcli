package com.agentcli.agent;

import com.agentcli.context.ContextProfile;
import com.agentcli.prompt.ApprovalMode;
import com.agentcli.prompt.PromptMode;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptTest {

    private final Function<String, String> originalLoader = SystemPrompt.templateLoader;

    @AfterEach
    void restoreLoader() {
        SystemPrompt.templateLoader = originalLoader;
    }

    private ContextProfile profile(String projectPath) {
        return new ContextProfile("glm", "glm-4.6", projectPath, 7, 0, 0, 0,
                0, 0, 0, 128_000);
    }

    @Test
    void templatePromptKeepsCorePhrases(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()));

        assertTrue(prompt.contains("You are AgentCLI"), prompt);
        assertTrue(prompt.contains("Project root: " + projectRoot), prompt);
        assertTrue(prompt.contains("Guidelines:"), prompt);
        assertTrue(prompt.contains("save_memory only when the user explicitly asks"), prompt);
        assertTrue(prompt.contains("grep_code"), prompt);
    }

    @Test
    void renderedPromptLeaksNoPlaceholders(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()));

        assertFalse(prompt.contains("{{"), prompt);
        assertFalse(prompt.contains("}}"), prompt);
    }

    @Test
    void profileInjectsToolCountAndModelName(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()));

        assertTrue(prompt.contains("7 tools registered"), prompt);
        assertTrue(prompt.contains("glm-4.6"), prompt);
    }

    @Test
    void blankModelNameDropsThatLineEntirely(@TempDir Path projectRoot) {
        ContextProfile noModel = new ContextProfile("glm", "", projectRoot.toString(), 3, 0, 0, 0,
                0, 0, 0, 128_000);

        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "", noModel);

        assertFalse(prompt.contains("served by the"), prompt);
        assertTrue(prompt.contains("3 tools registered"), prompt);
    }

    @Test
    void memorySectionAppearsOnlyWhenContextPresent(@TempDir Path projectRoot) {
        ToolRegistry registry = new ToolRegistry(projectRoot.toString());

        String without = SystemPrompt.forAgent(registry, "");
        assertFalse(without.contains("REMEMBERED FACT"), without);

        String with = SystemPrompt.forAgent(registry, "REMEMBERED FACT: user prefers Java 17");
        assertTrue(with.contains("REMEMBERED FACT: user prefers Java 17"), with);
        assertTrue(with.endsWith("REMEMBERED FACT: user prefers Java 17"), with);
    }

    @Test
    void blankMemoryContextIsIgnored(@TempDir Path projectRoot) {
        ToolRegistry registry = new ToolRegistry(projectRoot.toString());

        assertEquals(SystemPrompt.forAgent(registry, ""), SystemPrompt.forAgent(registry, "   "));
        assertEquals(SystemPrompt.forAgent(registry, ""), SystemPrompt.forAgent(registry, null));
    }

    @Test
    void missingTemplateFallsBackToLegacyPrompt(@TempDir Path projectRoot) {
        SystemPrompt.templateLoader = name -> null;

        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "");

        // 降级文本必须与 v0.15.0 硬编码内容逐字一致
        String expected = """
                You are AgentCLI, a helpful coding assistant running in a local project workspace.
                Project root: %s

                Guidelines:
                - Use tools when they help answer accurately (read_file, grep_code, glob_files, web_search, save_memory, etc.).
                - Prefer grep_code/glob_files/read_file for code navigation before guessing.
                - Use save_memory only when the user explicitly asks to remember a stable fact or preference.
                - Keep answers concise and actionable; match the user's language (Chinese or English).
                - When a tool returns an error or policy rejection, explain it and try a safer alternative.
                """.formatted(projectRoot.toString()).trim();

        assertEquals(expected, prompt);
    }

    @Test
    void legacyFallbackStillAppendsMemoryContext(@TempDir Path projectRoot) {
        SystemPrompt.templateLoader = name -> null;

        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()),
                "REMEMBERED FACT: uses Maven");

        assertTrue(prompt.contains("You are AgentCLI"), prompt);
        assertTrue(prompt.endsWith("REMEMBERED FACT: uses Maven"), prompt);
        assertFalse(prompt.contains("tools registered"), prompt);
    }

    @Test
    void partialTemplateLossAlsoFallsBackToLegacy(@TempDir Path projectRoot) {
        // 只有记忆段缺失，也必须整体降级，避免出现半模板半硬编码的混合提示词
        SystemPrompt.templateLoader = name -> "system_memory.md".equals(name)
                ? null
                : com.agentcli.prompt.PromptTemplateLoader.load(name);

        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "");

        assertFalse(prompt.contains("tools registered"), prompt);
        assertTrue(prompt.contains("You are AgentCLI"), prompt);
    }

    @Test
    void loaderExceptionIsSwallowedAndFallsBack(@TempDir Path projectRoot) {
        SystemPrompt.templateLoader = name -> {
            throw new IllegalStateException("boom");
        };

        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "");

        assertTrue(prompt.contains("You are AgentCLI"), prompt);
        assertFalse(prompt.contains("tools registered"), prompt);
    }

    @Test
    void nullRegistryDoesNotThrow() {
        String prompt = SystemPrompt.forAgent(null, "");
        assertTrue(prompt.contains("Project root: ."), prompt);
    }

    @Test
    void defaultModeKeepsCoreAndOmitsOtherModes(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()), PromptMode.DEFAULT, ApprovalMode.SUGGEST);
        assertTrue(prompt.contains("You are AgentCLI"), prompt);
        assertTrue(prompt.contains("工作模式：ReAct Agent"), prompt);
        assertTrue(prompt.contains("审批策略：建议确认"), prompt);
        // 默认模式不应泄漏 plan / review 专属片段
        assertFalse(prompt.contains("Plan-and-Execute"), prompt);
        assertFalse(prompt.contains("工作模式：Reviewer"), prompt);
    }

    @Test
    void planModeSegmentIsInjected(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()), PromptMode.PLAN, ApprovalMode.SUGGEST);
        assertTrue(prompt.contains("工作模式：Plan-and-Execute"), prompt);
        assertFalse(prompt.contains("工作模式：ReAct Agent"), prompt);
    }

    @Test
    void reviewModeSegmentIsInjected(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()), PromptMode.REVIEW, ApprovalMode.SUGGEST);
        assertTrue(prompt.contains("工作模式：Reviewer"), prompt);
    }

    @Test
    void autoApprovalSegmentIsInjected(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()), PromptMode.DEFAULT, ApprovalMode.AUTO);
        assertTrue(prompt.contains("审批策略：自动执行"), prompt);
        assertFalse(prompt.contains("审批策略：建议确认"), prompt);
    }

    @Test
    void neverApprovalSegmentIsInjected(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()), PromptMode.DEFAULT, ApprovalMode.NEVER);
        assertTrue(prompt.contains("审批策略：保守优先"), prompt);
    }

    @Test
    void unknownModeAndApprovalFallBackToDefaults(@TempDir Path projectRoot) {
        String prompt = SystemPrompt.forAgent(new ToolRegistry(projectRoot.toString()), "",
                profile(projectRoot.toString()), PromptMode.from("bogus"), ApprovalMode.from("weird"));
        assertTrue(prompt.contains("工作模式：ReAct Agent"), prompt);
        assertTrue(prompt.contains("审批策略：建议确认"), prompt);
    }

    @Test
    void stablePrefixUnaffectedByMemoryContextForCache(@TempDir Path projectRoot) {
        // 缓存友好布局：mode/approval 等稳定片段排在前，记忆段（每轮变化）排最后。
        // 因此相同 mode/approval 下，带记忆与不带记忆的提示词应共享同一前缀。
        ToolRegistry registry = new ToolRegistry(projectRoot.toString());
        ContextProfile p = profile(projectRoot.toString());
        String withoutMemory = SystemPrompt.forAgent(registry, "", p, PromptMode.PLAN, ApprovalMode.NEVER);
        String withMemory = SystemPrompt.forAgent(registry, "REMEMBERED FACT: prefers Java 17", p,
                PromptMode.PLAN, ApprovalMode.NEVER);
        assertTrue(withMemory.startsWith(withoutMemory), "memory 段应仅追加在末尾，稳定前缀必须一致");
        assertTrue(withMemory.endsWith("REMEMBERED FACT: prefers Java 17"), withMemory);
    }
}
