package com.agentcli.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillIndexFormatterTest {

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "1.0", "tester", List.of(),
                Skill.Source.USER, "body", null, null);
    }

    @Test
    void emptyListProducesEmptyString() {
        assertEquals("", SkillIndexFormatter.format(List.of()));
        assertEquals("", SkillIndexFormatter.format(null));
    }

    @Test
    void normalFormatContainsHeaderAndSkill() {
        String index = SkillIndexFormatter.format(List.of(skill("web", "web desc")));
        assertTrue(index.contains("## 可用 Skills（按需调用 load_skill 加载完整指引）"));
        assertTrue(index.contains("- **web**：web desc"));
        assertTrue(index.contains("判断准则"));
    }

    @Test
    void longDescriptionIsTruncated() {
        String longDesc = "a".repeat(600);
        String index = SkillIndexFormatter.format(List.of(skill("big", longDesc)));
        assertTrue(index.contains("..."), "expected truncation marker");
        assertFalse(index.contains("a".repeat(501)), "description exceeds 500 codepoints");
    }

    @Test
    void onlyFirstTwentySkillsAreKept() {
        List<Skill> skills = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            skills.add(skill(String.format("s%02d", i), "desc " + i));
        }
        String index = SkillIndexFormatter.format(skills);
        int bullets = countOccurrences(index, "- **");
        assertEquals(SkillIndexFormatter.MAX_ENABLED_SKILLS, bullets);
    }

    @Test
    void totalSegmentIsTruncatedWhenTooLarge() {
        List<Skill> skills = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            skills.add(skill("skill-" + i, "a".repeat(500)));
        }
        String index = SkillIndexFormatter.format(skills);
        assertTrue(index.contains("...(skill 索引段被截断)"));
    }

    @Test
    void specialCharactersInNameAndDescriptionAreSafe() {
        Skill s = new Skill("*weird*", "desc with `backtick` and <html> & 'quotes'",
                "1.0", "tester", List.of(), Skill.Source.USER, "body", null, null);
        String index = SkillIndexFormatter.format(List.of(s));
        assertTrue(index.contains("*weird*"), index);
        assertTrue(index.contains("backtick"), index);
        assertTrue(index.contains("## 可用 Skills"), index);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
