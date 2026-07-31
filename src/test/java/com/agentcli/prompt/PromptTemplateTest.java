package com.agentcli.prompt;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateTest {

    // ---------- Loader ----------

    @Test
    void loadsBundledSystemTemplates() {
        assertNotNull(PromptTemplateLoader.load("system_role.md"));
        assertNotNull(PromptTemplateLoader.load("system_tools.md"));
        assertNotNull(PromptTemplateLoader.load("system_memory.md"));
    }

    @Test
    void bundledTemplatesCarryExpectedPlaceholders() {
        assertTrue(PromptTemplateLoader.load("system_role.md").contains("{{project_path}}"));
        assertTrue(PromptTemplateLoader.load("system_tools.md").contains("{{tool_count}}"));
        assertTrue(PromptTemplateLoader.load("system_tools.md").contains("{{model_name}}"));
        assertTrue(PromptTemplateLoader.load("system_memory.md").contains("{{memory_context}}"));
    }

    @Test
    void repeatedLoadsHitCacheAndReturnSameInstance() {
        PromptTemplateLoader.clearCache();
        String first = PromptTemplateLoader.load("system_role.md");
        String second = PromptTemplateLoader.load("system_role.md");

        assertNotNull(first);
        // 命中缓存时应返回同一个 String 实例，而不是重新读一遍 classpath
        assertSame(first, second);
    }

    @Test
    void clearCacheForcesReload() {
        String first = PromptTemplateLoader.load("system_role.md");
        PromptTemplateLoader.clearCache();
        String reloaded = PromptTemplateLoader.load("system_role.md");

        assertEquals(first, reloaded);
    }

    @Test
    void missingTemplateReturnsNullInsteadOfThrowing() {
        assertNull(PromptTemplateLoader.load("definitely_missing_template.md"));
    }

    @Test
    void missingTemplateResultIsCachedAndStableAcrossCalls() {
        assertNull(PromptTemplateLoader.load("still_missing.md"));
        assertNull(PromptTemplateLoader.load("still_missing.md"));
    }

    @Test
    void rejectsParentTraversalAndAbsolutePaths() {
        assertNull(PromptTemplateLoader.load("../secret.md"));
        assertNull(PromptTemplateLoader.load("../../etc/passwd"));
        assertNull(PromptTemplateLoader.load("/etc/passwd"));
        assertNull(PromptTemplateLoader.load("\\windows\\system32"));
        assertNull(PromptTemplateLoader.load("C:/windows/system.ini"));
    }

    @Test
    void rejectsNullAndBlankNames() {
        assertNull(PromptTemplateLoader.load(null));
        assertNull(PromptTemplateLoader.load(""));
        assertNull(PromptTemplateLoader.load("   "));
    }

    // ---------- Renderer ----------

    @Test
    void rendersKnownVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("project_path", "/tmp/proj");
        vars.put("tool_count", "12");

        String result = PromptTemplateRenderer.render("root={{project_path}} tools={{tool_count}}", vars);

        assertEquals("root=/tmp/proj tools=12", result);
    }

    @Test
    void unknownPlaceholdersArePreservedVerbatim() {
        String result = PromptTemplateRenderer.render("a={{known}} b={{unknown}}",
                Map.of("known", "1"));

        assertEquals("a=1 b={{unknown}}", result);
    }

    @Test
    void nullValueRendersAsEmptyString() {
        Map<String, String> vars = new HashMap<>();
        vars.put("model_name", null);

        assertEquals("model=", PromptTemplateRenderer.render("model={{model_name}}", vars));
    }

    @Test
    void repeatedPlaceholdersAreAllReplaced() {
        String result = PromptTemplateRenderer.render("{{x}}-{{x}}-{{x}}", Map.of("x", "9"));
        assertEquals("9-9-9", result);
    }

    @Test
    void nullTemplateYieldsEmptyStringAndEmptyVarsKeepsTemplate() {
        assertEquals("", PromptTemplateRenderer.render(null, Map.of("a", "b")));
        assertEquals("{{a}}", PromptTemplateRenderer.render("{{a}}", Map.of()));
        assertEquals("{{a}}", PromptTemplateRenderer.render("{{a}}", null));
    }
}
