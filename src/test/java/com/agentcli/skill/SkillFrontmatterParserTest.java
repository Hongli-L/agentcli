package com.agentcli.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFrontmatterParserTest {

    @Test
    void parsesSingleLineAndInlineArray() {
        String md = "---\nname: web\nversion: \"1.0\"\ntags: [a, b, c]\n---\nBody text here.\n";
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse(md);
        assertTrue(result.warnings().isEmpty(), "unexpected warnings: " + result.warnings());
        Map<String, Object> fm = result.frontmatter();
        assertEquals("web", fm.get("name"));
        assertEquals("1.0", fm.get("version"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) fm.get("tags");
        assertEquals(List.of("a", "b", "c"), tags);
        assertEquals("Body text here.\n", result.body());
    }

    @Test
    void parsesMultiLineBlock() {
        String md = "---\nname: b\ndescription: |\n  line one\n  line two\n---\nbody\n";
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse(md);
        assertTrue(result.warnings().isEmpty(), "unexpected warnings: " + result.warnings());
        assertEquals("line one line two", result.frontmatter().get("description"));
        assertEquals("body\n", result.body());
    }

    @Test
    void missingStartMarkerProducesWarning() {
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse("no front matter at all");
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.body().contains("no front matter"));
    }

    @Test
    void nullInputProducesWarning() {
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse(null);
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void missingEndMarkerProducesWarning() {
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse("---\nname: x\n");
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void nestedObjectIsSkippedWithWarning() {
        String md = "---\nname: x\nmeta: { a: 1 }\n---\nbody\n";
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse(md);
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.frontmatter().containsKey("name"));
        assertTrue(result.body().startsWith("body"));
    }

    @Test
    void emptyValueFieldProducesWarning() {
        String md = "---\nname: x\ndescription:\n---\nbody\n";
        SkillFrontmatterParser.ParseResult result = SkillFrontmatterParser.parse(md);
        assertFalse(result.warnings().isEmpty(), "expected warning for empty value field");
        assertTrue(result.frontmatter().containsKey("name"));
        assertFalse(result.frontmatter().containsKey("description"));
        assertTrue(result.body().startsWith("body"));
    }
}
