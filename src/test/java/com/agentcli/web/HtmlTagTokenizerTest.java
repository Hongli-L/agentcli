package com.agentcli.web;

import com.agentcli.web.HtmlTagTokenizer.Kind;
import com.agentcli.web.HtmlTagTokenizer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态机切分用例：纯字符串进、token 列表出，不涉及任何 IO。 */
class HtmlTagTokenizerTest {

    private final HtmlTagTokenizer tokenizer = new HtmlTagTokenizer();

    private static Token firstOf(List<Token> tokens, Kind kind, String name) {
        return tokens.stream()
                .filter(t -> t.kind() == kind && t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Test
    void splitsStartTextAndEndTokens() {
        List<Token> tokens = tokenizer.tokenize("<p>hello</p>");

        assertEquals(3, tokens.size());
        assertEquals(Kind.START_TAG, tokens.get(0).kind());
        assertEquals("p", tokens.get(0).name());
        assertEquals(Kind.TEXT, tokens.get(1).kind());
        assertEquals("hello", tokens.get(1).text());
        assertEquals(Kind.END_TAG, tokens.get(2).kind());
        assertEquals("p", tokens.get(2).name());
    }

    @Test
    void lowercasesTagAndAttributeNames() {
        List<Token> tokens = tokenizer.tokenize("<DIV CLASS=\"Main\">x</DIV>");

        assertEquals("div", tokens.get(0).name());
        assertEquals("Main", tokens.get(0).attr("class"));
        assertEquals("div", tokens.get(2).name());
    }

    @Test
    void parsesAllThreeAttributeValueStyles() {
        List<Token> tokens = tokenizer.tokenize(
                "<a href=\"/a b\" title='单引号' data-x=bare rel>text</a>");
        Token anchor = tokens.get(0);

        assertEquals("/a b", anchor.attr("href"));
        assertEquals("单引号", anchor.attr("title"));
        assertEquals("bare", anchor.attr("data-x"));
        assertEquals("", anchor.attr("rel"));
        assertEquals("", anchor.attr("absent"));
    }

    @Test
    void treatsScriptContentAsRawText() {
        List<Token> tokens = tokenizer.tokenize(
                "<div><script>var a = \"<div>not a tag</div>\";</script></div>");

        // script 内部的尖括号不得参与切分，否则会凭空多出 div 标签
        long divStarts = tokens.stream()
                .filter(t -> t.kind() == Kind.START_TAG && t.name().equals("div"))
                .count();
        assertEquals(1, divStarts);

        Token raw = tokens.stream()
                .filter(t -> t.kind() == Kind.TEXT)
                .findFirst()
                .orElseThrow();
        assertTrue(raw.text().contains("not a tag"), raw.text());
    }

    @Test
    void marksVoidAndSelfClosingTags() {
        List<Token> tokens = tokenizer.tokenize("<p>a<br/>b<img src=\"/i.png\">c</p>");

        Token br = firstOf(tokens, Kind.SELF_CLOSING, "br");
        Token img = firstOf(tokens, Kind.SELF_CLOSING, "img");
        assertTrue(br != null, "br 应识别为自闭合");
        assertTrue(img != null, "img 是 void 标签，应识别为自闭合");
        assertEquals("/i.png", img.attr("src"));
        assertTrue(img.opens());
    }

    @Test
    void emitsCommentAndDoctypeTokens() {
        List<Token> tokens = tokenizer.tokenize("<!DOCTYPE html><!-- 注释 --><p>x</p>");

        assertEquals(Kind.DOCTYPE, tokens.get(0).kind());
        assertEquals(Kind.COMMENT, tokens.get(1).kind());
        assertTrue(tokens.get(1).text().contains("注释"), tokens.get(1).text());
        assertEquals(Kind.START_TAG, tokens.get(2).kind());
    }

    @Test
    void treatsStrayAngleBracketAsText() {
        List<Token> tokens = tokenizer.tokenize("<p>if a < b then</p>");

        String text = tokens.stream()
                .filter(t -> t.kind() == Kind.TEXT)
                .map(Token::text)
                .reduce("", String::concat);
        assertTrue(text.contains("a < b"), text);
    }

    @Test
    void malformedInputDoesNotThrow() {
        assertDoesNotThrow(() -> tokenizer.tokenize("<div><p>未闭合"));
        assertDoesNotThrow(() -> tokenizer.tokenize("<<>><a href=\"unterminated"));
        assertDoesNotThrow(() -> tokenizer.tokenize("<!-- 未闭合注释"));
        assertDoesNotThrow(() -> tokenizer.tokenize("<script>没有结束标签"));
        assertTrue(tokenizer.tokenize(null).isEmpty());
        assertTrue(tokenizer.tokenize("").isEmpty());
    }

    @Test
    void closingTagWithoutNameIsTreatedAsText() {
        List<Token> tokens = tokenizer.tokenize("a </ b");

        assertFalse(tokens.stream().anyMatch(t -> t.kind() == Kind.END_TAG));
    }
}
