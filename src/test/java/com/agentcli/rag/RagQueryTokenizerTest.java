package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RagQueryTokenizer} 单元测试。
 */
class RagQueryTokenizerTest {

    @Test
    @DisplayName("中文按 bigram 切分")
    void chineseIsSplitIntoBigrams() {
        Set<String> tokens = RagQueryTokenizer.tokenize("用户登录");

        assertTrue(tokens.contains("用户"));
        assertTrue(tokens.contains("户登"));
        assertTrue(tokens.contains("登录"));
    }

    @Test
    @DisplayName("驼峰标识符被拆成整体与片段")
    void camelCaseIsSplit() {
        Set<String> tokens = RagQueryTokenizer.tokenize("AuthService");

        assertTrue(tokens.contains("authservice"), "应保留整体小写形式");
        assertTrue(tokens.contains("auth"));
        assertTrue(tokens.contains("service"));
    }

    @Test
    @DisplayName("下划线与连续大写同样被正确拆分")
    void underscoreAndAcronymAreSplit() {
        Set<String> tokens = RagQueryTokenizer.tokenize("HTTPClient max_retry");

        assertTrue(tokens.contains("http"));
        assertTrue(tokens.contains("client"));
        assertTrue(tokens.contains("max"));
        assertTrue(tokens.contains("retry"));
    }

    @Test
    @DisplayName("停用词被过滤")
    void stopwordsAreFiltered() {
        Set<String> tokens = RagQueryTokenizer.tokenize("如何 实现 the login");

        assertFalse(tokens.contains("如何"));
        assertFalse(tokens.contains("实现"));
        assertFalse(tokens.contains("the"));
        assertTrue(tokens.contains("login"));
    }

    @Test
    @DisplayName("长度小于 2 的 token 被丢弃")
    void shortTokensAreDropped() {
        Set<String> tokens = RagQueryTokenizer.tokenize("a b c login x");

        assertEquals(Set.of("login"), tokens);
    }

    @Test
    @DisplayName("token 数量上限为 8")
    void tokenCountIsCapped() {
        Set<String> tokens = RagQueryTokenizer.tokenize(
                "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo");

        assertEquals(RagQueryTokenizer.MAX_TOKENS, tokens.size());
    }

    @Test
    @DisplayName("中英混排与空输入均安全")
    void mixedAndBlankInputAreSafe() {
        Set<String> tokens = RagQueryTokenizer.tokenize("AuthService的登录逻辑");

        assertTrue(tokens.contains("auth"));
        assertTrue(tokens.contains("登录"));
        assertTrue(RagQueryTokenizer.tokenize(null).isEmpty());
        assertTrue(RagQueryTokenizer.tokenize("   ").isEmpty());
        assertTrue(RagQueryTokenizer.tokenize("!!! ???").isEmpty());
    }
}
