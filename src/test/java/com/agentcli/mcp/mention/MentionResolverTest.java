package com.agentcli.mcp.mention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 @mention 解析内核：相对 / 绝对路径内联、~ 展开受约束、越界拒绝、
 * 32KB 截断、内联块格式、MENTION_PATTERN 不误伤 user@host、逗号分隔、注入失败标记、JSON 注入。
 */
public class MentionResolverTest {

    @TempDir Path projectDir;

    private MentionResolver resolver() {
        return new MentionResolver(projectDir);
    }

    @Test
    void resolvesRelativeFileToInlineBlock() throws IOException {
        Path file = projectDir.resolve("README.md");
        Files.writeString(file, "hello content");
        String out = resolver().resolve("see @README.md please");
        assertTrue(out.contains("<file path=\"README.md\">"), out);
        assertTrue(out.contains("hello content"), out);
        assertTrue(out.contains("</file>"), out);
    }

    @Test
    void resolvesAbsolutePathInsideProjectDir() throws IOException {
        Path file = projectDir.resolve("src/main/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}");
        String abs = file.toAbsolutePath().normalize().toString();
        String out = resolver().resolve("check @" + abs);
        assertTrue(out.contains("<file path="), out);
        assertTrue(out.contains("src/main/App.java"), out);
        assertTrue(out.contains("class App {}"), out);
    }

    @Test
    void rejectsTraversalOutsideProjectDir() throws IOException {
        Path file = projectDir.resolve("inner.txt");
        Files.writeString(file, "x");
        String out = resolver().resolve("oops @../secret.txt");
        assertTrue(out.contains("[mention error:"), out);
        assertFalse(out.contains("<file"), out);
    }

    @Test
    void rejectsAbsolutePathOutsideProjectDir() throws IOException {
        Path file = projectDir.resolve("inner.txt");
        Files.writeString(file, "x");
        String outside = projectDir.getRoot().resolve("outside.txt").toString();
        String out = resolver().resolve("oops @" + outside);
        assertTrue(out.contains("[mention error:"), out);
    }

    @Test
    void tildeExpansionIsConstrainedByProjectDir() throws IOException {
        Path file = projectDir.resolve("inner.txt");
        Files.writeString(file, "x");
        // ~/inner.txt 展开为 user.home/inner.txt，几乎总在项目目录外 -> 被拒
        String out = resolver().resolve("oops @~/inner.txt");
        assertTrue(out.contains("[mention error:"), out);
    }

    @Test
    void truncatesFilesLargerThan32Kb() throws IOException {
        Path file = projectDir.resolve("big.txt");
        byte[] big = new byte[MentionPolicy.MAX_INLINE_BYTES + 100];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 26));
        }
        Files.write(file, big);
        String out = resolver().resolve("big @big.txt");
        assertTrue(out.contains("[truncated:"), out);
        assertTrue(out.contains("<file path=\"big.txt\">"), out);
    }

    @Test
    void mentionPatternDoesNotMatchUserAtHost() {
        String out = resolver().resolve("contact user@host.com now");
        assertTrue(out.contains("user@host.com"), out);
        assertFalse(out.contains("<file"), out);
    }

    @Test
    void resolvesCommaSeparatedMentions() throws IOException {
        Path a = projectDir.resolve("a.txt");
        Path b = projectDir.resolve("b.txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");
        String out = resolver().resolve("compare @a.txt,@b.txt");
        assertTrue(out.contains("<file path=\"a.txt\">"), out);
        assertTrue(out.contains("AAA"), out);
        assertTrue(out.contains("<file path=\"b.txt\">"), out);
        assertTrue(out.contains("BBB"), out);
    }

    @Test
    void missingFileProducesErrorMarker() {
        String out = resolver().resolve("read @missing.txt");
        assertTrue(out.contains("[mention error:"), out);
    }

    @Test
    void blankTextReturnsUnchanged() {
        assertEquals("", resolver().resolve(""));
        assertEquals("   ", resolver().resolve("   "));
    }

    @Test
    void resolveJsonInjectsIntoStringLeaves() throws IOException {
        Path file = projectDir.resolve("README.md");
        Files.writeString(file, "doc body");
        String json = "{\"q\":\"@README.md\",\"n\":1}";
        String out = resolver().resolveJson(json);
        assertTrue(out.contains("<file path="), out);
        assertTrue(out.contains("README.md"), out);
        assertTrue(out.contains("doc body"), out);
        assertTrue(out.contains("\"n\":1"), out);
    }

    @Test
    void resolveJsonWithoutMentionReturnsOriginal() {
        String json = "{\"q\":\"plain\",\"n\":2}";
        assertEquals(json, resolver().resolveJson(json));
    }
}
