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
}
