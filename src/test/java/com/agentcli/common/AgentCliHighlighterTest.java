package com.agentcli.common;

import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCliHighlighterTest {

    private static AttributedStyle defaultStyle() {
        return AttributedStyle.DEFAULT;
    }

    private static AttributedStyle fg(int color) {
        return AttributedStyle.DEFAULT.foreground(color);
    }

    private static AttributedStyle fgBold(int color) {
        return AttributedStyle.DEFAULT.foreground(color).bold();
    }

    @Test
    void disabledReturnsOriginalBuffer() {
        AgentCliHighlighter hl = new AgentCliHighlighter(false);
        AttributedString result = hl.highlight(null, "/plan say \"hi\"");
        assertEquals("/plan say \"hi\"", result.toString());
    }

    @Test
    void emptyBufferReturnsEmpty() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        assertEquals("", hl.highlight(null, "").toString());
    }

    @Test
    void slashCommandIsKeywordColored() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        AttributedString result = hl.highlight(null, "/plan");
        AttributedStyle expected = fg(AnsiStyle.keywordColor());
        for (int i = 0; i < result.length(); i++) {
            assertEquals(expected, result.styleAt(i),
                    "char at " + i + " should be keyword-colored");
        }
    }

    @Test
    void quotedStringIsStringColored() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "say \"hello world\"";
        AttributedString result = hl.highlight(null, buffer);
        int quoteIndex = buffer.indexOf('"');
        AttributedStyle expected = fg(AnsiStyle.stringColor());
        assertEquals(expected, result.styleAt(quoteIndex),
                "opening quote should be string-colored");
        assertEquals(expected, result.styleAt(quoteIndex + 1),
                "char inside quotes should be string-colored");
    }

    @Test
    void localPathIsPathColored() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "cat src/main/java/App.java";
        AttributedString result = hl.highlight(null, buffer);
        int pathStart = buffer.indexOf("src");
        assertEquals(fg(AnsiStyle.pathColor()), result.styleAt(pathStart),
                "path token should be path-colored");
    }

    @Test
    void plainTextNotColored() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        AttributedString result = hl.highlight(null, "hello world");
        AttributedStyle plain = defaultStyle();
        for (int i = 0; i < result.length(); i++) {
            assertFalse(result.styleAt(i).equals(fg(AnsiStyle.keywordColor())),
                    "char at " + i + " must not be keyword-colored");
            assertFalse(result.styleAt(i).equals(fg(AnsiStyle.pathColor())),
                    "char at " + i + " must not be path-colored");
        }
    }

    @Test
    void errorIndexIsHighlightedRed() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        hl.setErrorIndex(2); // "/plan" 中第 2 个字符为 'l'
        AttributedString result = hl.highlight(null, "/plan");
        assertEquals(fg(31), result.styleAt(2),
                "char at errorIndex should be red");
    }

    @Test
    void errorPatternMatchesAreHighlightedRed() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        hl.setErrorPattern(Pattern.compile("bad"));
        String buffer = "use bad token";
        AttributedString result = hl.highlight(null, buffer);
        int badStart = buffer.indexOf("bad");
        assertEquals(fg(31), result.styleAt(badStart),
                "pattern-matched char should be red");
    }

    @Test
    void dangerCommandRmRfIsHighlightedBoldRed() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "rm -rf /tmp/foo";
        AttributedString result = hl.highlight(null, buffer);
        int rmStart = buffer.indexOf("rm");
        assertEquals(fgBold(AnsiStyle.dangerColor()), result.styleAt(rmStart),
                "rm -rf should be bold-red (danger)");
        int dashRf = buffer.indexOf("-rf");
        assertEquals(fgBold(AnsiStyle.dangerColor()), result.styleAt(dashRf),
                "-rf flag should be bold-red (danger)");
    }

    @Test
    void sudoIsHighlightedBoldRed() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "sudo rm file";
        AttributedString result = hl.highlight(null, buffer);
        int sudoStart = buffer.indexOf("sudo");
        assertEquals(fgBold(AnsiStyle.dangerColor()), result.styleAt(sudoStart),
                "sudo should be bold-red (danger)");
    }

    @Test
    void secretAssignmentIsHighlightedBoldYellow() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "export API_KEY=abc123";
        AttributedString result = hl.highlight(null, buffer);
        int keyStart = buffer.indexOf("API_KEY");
        assertEquals(fgBold(AnsiStyle.secretColor()), result.styleAt(keyStart),
                "API_KEY=... assignment should be bold-yellow (secret)");
        int valStart = buffer.indexOf("abc123");
        assertEquals(fgBold(AnsiStyle.secretColor()), result.styleAt(valStart),
                "secret value should also be bold-yellow");
    }

    @Test
    void bearerTokenIsHighlightedBoldYellow() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "Authorization: Bearer eyJabc.def";
        AttributedString result = hl.highlight(null, buffer);
        int bearerStart = buffer.indexOf("Bearer");
        assertEquals(fgBold(AnsiStyle.secretColor()), result.styleAt(bearerStart),
                "Bearer token should be bold-yellow (secret)");
    }

    @Test
    void unclosedQuoteIsHighlightedBoldRed() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "echo \"unterminated";
        AttributedString result = hl.highlight(null, buffer);
        int quoteStart = buffer.indexOf('"');
        assertEquals(fgBold(AnsiStyle.dangerColor()), result.styleAt(quoteStart),
                "unclosed quote should be bold-red (danger)");
        int contentStart = buffer.indexOf("unterminated");
        assertEquals(fgBold(AnsiStyle.dangerColor()), result.styleAt(contentStart),
                "unclosed quote content should be bold-red");
    }

    @Test
    void safeCommandNotDangerouslyColored() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        String buffer = "git status";
        AttributedString result = hl.highlight(null, buffer);
        for (int i = 0; i < result.length(); i++) {
            assertFalse(result.styleAt(i).equals(fgBold(AnsiStyle.dangerColor())),
                    "char at " + i + " must not be danger-colored");
            assertFalse(result.styleAt(i).equals(fgBold(AnsiStyle.secretColor())),
                    "char at " + i + " must not be secret-colored");
        }
    }

    @Test
    void errorOutranksDangerColoring() {
        AgentCliHighlighter hl = new AgentCliHighlighter(true);
        hl.setErrorIndex(0); // 命中 rm 的 'r'
        String buffer = "rm -rf /tmp/foo";
        AttributedString result = hl.highlight(null, buffer);
        assertEquals(fg(31), result.styleAt(0),
                "explicit error index should win over danger (plain red, not bold)");
    }
}
