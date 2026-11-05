package com.agentcli.mcp.mention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    @Test
    void resolvesAbsolutePathInsideExtraRoot() throws IOException {
        Path extra = projectDir.resolve("..").resolve("extraRoot").toAbsolutePath().normalize();
        Files.createDirectories(extra);
        Path file = extra.resolve("note.txt");
        Files.writeString(file, "extra body");
        MentionResolver r = new MentionResolver(projectDir, extra);
        String out = r.resolve("see @" + file);
        assertTrue(out.contains("<file path="), out);
        assertTrue(out.contains("note.txt"), out);
        assertTrue(out.contains("extra body"), out);
        // 额外根非 home，relPath 用相对额外根、不带 ~/ 前缀
        assertFalse(out.contains("~/"), out);
    }

    @Test
    void tildeUnderHomeUsesTildePrefix() throws IOException {
        Path home = projectDir.resolve("..").resolve("fakeHome").toAbsolutePath().normalize();
        Files.createDirectories(home);
        Path file = home.resolve("Desktop").resolve("foo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Foo {}");
        String prev = System.setProperty("user.home", home.toString());
        try {
            MentionResolver r = new MentionResolver(projectDir, home);
            String out = r.resolve("open @~/Desktop/foo.java");
            assertTrue(out.contains("<file path=\"~/Desktop/foo.java\">"), out);
            assertTrue(out.contains("class Foo {}"), out);
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    @Test
    void tildeSensitivePathUnderHomeBlocked() throws IOException {
        Path home = projectDir.resolve("..").resolve("fakeHome2").toAbsolutePath().normalize();
        Files.createDirectories(home);
        Path secret = home.resolve(".ssh").resolve("id_rsa");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "PRIVATE KEY");
        String prev = System.setProperty("user.home", home.toString());
        try {
            MentionResolver r = new MentionResolver(projectDir, home);
            String out = r.resolve("read @~/.ssh/id_rsa");
            assertTrue(out.contains("[mention error:"), out);
            assertFalse(out.contains("PRIVATE KEY"), out);
            assertTrue(out.contains("sensitive"), out);
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    @Test
    void extraRootDoesNotLeakOutsideItsBoundary() throws IOException {
        Path extra = projectDir.resolve("..").resolve("extraRoot2").toAbsolutePath().normalize();
        Files.createDirectories(extra);
        Path outside = projectDir.resolve("..").resolve("outsideRoot.txt").toAbsolutePath().normalize();
        Files.writeString(outside, "x");
        MentionResolver r = new MentionResolver(projectDir, extra);
        String out = r.resolve("oops @" + outside);
        assertTrue(out.contains("[mention error:"), out);
        assertFalse(out.contains("<file"), out);
    }

    @Test
    void friendlyReasonWhenBlockedWithoutExtraRoots() {
        // 默认构造（无额外根）：~/x 展开到真实 home，不在项目内 -> 拒，原因可读
        String out = resolver().resolve("oops @~/x");
        assertTrue(out.contains("[mention error:"), out);
        assertTrue(out.contains("项目目录") || out.contains("主目录"), out);
    }
}
