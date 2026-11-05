package com.agentcli.mcp.mention;

import com.agentcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMentionParserTest {

    @TempDir
    Path projectDir;

    private LlmClient.Message parseWithImage(String input) throws IOException {
        Path img = writePng(projectDir.resolve("shot.png"));
        MentionResolver resolver = new MentionResolver(projectDir);
        return ImageMentionParser.userMessage(input.replace("{IMG}", img.toString()), projectDir, resolver);
    }

    @Test
    void imageReferenceProducesMultimodalMessage() throws IOException {
        LlmClient.Message msg = parseWithImage("看这张图 @image:{IMG}");
        assertNotNull(msg.parts());
        assertTrue(msg.hasImageParts());
        // 文本部件包含看图指令
        assertTrue(msg.textContent().contains("请直接观察本轮图片内容"));
        // 恰好一个图片部件，data URI 正确
        long imageCount = msg.parts().stream().filter(LlmClient.ContentPart::isImage).count();
        assertEquals(1, imageCount);
        LlmClient.ContentPart image = msg.parts().stream()
                .filter(LlmClient.ContentPart::isImage).findFirst().orElseThrow();
        assertTrue(image.imageUrl().startsWith("data:image/png;base64,"), image.imageUrl());
    }

    @Test
    void plainTextWithoutImageStaysTextOnly() throws IOException {
        MentionResolver resolver = new MentionResolver(projectDir);
        LlmClient.Message msg = ImageMentionParser.userMessage("普通文本没有图片", projectDir, resolver);
        // 无图片时直接退回纯文本 Message.user(String)，parts 为 null
        assertEquals(null, msg.parts());
        assertFalse(msg.hasImageParts());
        assertEquals("普通文本没有图片", msg.textContent());
    }

    @Test
    void invalidImagePathYieldsFriendlyNoteNotImage() throws IOException {
        MentionResolver resolver = new MentionResolver(projectDir);
        LlmClient.Message msg = ImageMentionParser.userMessage("@image:/no/such/file.png", projectDir, resolver);
        // 越界路径被策略拒，不携带图片，但给出可读原因而非崩溃
        assertFalse(msg.hasImageParts());
        assertTrue(msg.textContent().contains("[图片引用无效"));
        assertTrue(msg.textContent().contains("文件不存在") || msg.textContent().contains("项目目录"));
    }

    @Test
    void clipboardInHeadlessYieldsNoteNotCrash() throws IOException {
        MentionResolver resolver = new MentionResolver(projectDir);
        LlmClient.Message msg = ImageMentionParser.userMessage("@clipboard 这是什么", projectDir, resolver);
        // 测试环境多为 headless，剪贴板抓取失败应降级为 note，不得抛异常
        assertFalse(msg.hasImageParts());
        assertTrue(msg.textContent().contains("[图片引用无效") || msg.textContent().contains("剪贴板"));
    }

    @Test
    void imageOutsideProjectDirBlockedByMentionResolver() throws IOException {
        // 解析器只给项目根，不带额外根；项目外的图片应被拒
        Path outside = projectDir.resolve("..").resolve("outside.png").normalize();
        writePng(outside);
        MentionResolver resolver = new MentionResolver(projectDir);
        LlmClient.Message msg = ImageMentionParser.userMessage("@image:" + outside, projectDir, resolver);
        assertFalse(msg.hasImageParts());
        assertTrue(msg.textContent().contains("[图片引用无效"));
    }

    private static Path writePng(Path path) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
        return path;
    }
}
