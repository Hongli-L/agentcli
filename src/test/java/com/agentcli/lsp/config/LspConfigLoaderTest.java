package com.agentcli.lsp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspConfigLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultConfigHasJava() {
        LspConfig cfg = new LspConfig();
        assertTrue(cfg.hasServer("java"));
        assertEquals("java", cfg.server("java").command());
        assertTrue(cfg.isAutoFeedback());
        assertEquals("changed", cfg.feedbackScope());
    }

    @Test
    void loadsProjectConfigAndExpandsProjectDir(@TempDir Path projectDir) throws Exception {
        Path dir = projectDir.resolve(".agentcli");
        Files.createDirectories(dir);
        String json = "{"
                + "\"servers\":{\"java\":{"
                + "\"languageId\":\"java\",\"command\":\"java\","
                + "\"args\":[\"--root\",\"${PROJECT_DIR}\"],"
                + "\"rootPath\":\"${PROJECT_DIR}\"}},"
                + "\"autoFeedback\":false,\"feedbackScope\":\"all\"}";
        Files.writeString(dir.resolve("lsp.json"), json);

        LspConfigLoader loader = new LspConfigLoader(projectDir.toString());
        LspConfig cfg = loader.load();

        assertTrue(cfg.hasServer("java"));
        assertEquals("java", cfg.server("java").command());
        assertEquals(projectDir.toString(), cfg.server("java").rootPath());
        assertEquals(projectDir.toString(), cfg.server("java").args().get(1));
        assertFalse(cfg.isAutoFeedback());
        assertEquals("all", cfg.feedbackScope());
    }

    @Test
    void mergesUserAndProject(@TempDir Path projectDir) throws Exception {
        Path userDir = projectDir.resolve("userhome").resolve(".agentcli");
        Path projDir = projectDir.resolve("proj").resolve(".agentcli");
        Files.createDirectories(userDir);
        Files.createDirectories(projDir);

        Files.writeString(userDir.resolve("lsp.json"),
                "{\"servers\":{\"python\":{\"languageId\":\"python\",\"command\":\"pylsp\",\"args\":[]}}}");
        // 让 projectDir 指向 proj，但 user 配置通过系统 user.home 读取
        String json = "{\"servers\":{\"java\":{\"languageId\":\"java\",\"command\":\"java\","
                + "\"args\":[\"x\"]}},\"autoFeedback\":false}";
        Files.writeString(projDir.resolve("lsp.json"), json);

        LspConfigLoader loader = new LspConfigLoader(projectDir.resolve("proj").toString());
        // 通过临时覆盖 user.home 让 user 配置被读到
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", userDir.getParent().toString());
            LspConfig cfg = loader.load();
            assertTrue(cfg.hasServer("java"), "项目配置应生效");
            assertTrue(cfg.hasServer("python"), "用户配置应被合并");
            assertFalse(cfg.isAutoFeedback());
        } finally {
            if (oldHome != null) {
                System.setProperty("user.home", oldHome);
            }
        }
    }

    @Test
    void missingFilesYieldsDefault(@TempDir Path projectDir) {
        LspConfig cfg = new LspConfigLoader(projectDir.toString()).load();
        assertTrue(cfg.hasServer("java"));
    }

    @Test
    void saveAndReloadRoundTrip(@TempDir Path projectDir) throws Exception {
        LspConfig cfg = new LspConfig();
        cfg.setAutoFeedback(false);
        cfg.setFeedbackScope("all");
        Path file = projectDir.resolve(".agentcli").resolve("lsp.json");
        LspConfigLoader.save(cfg, file);

        assertTrue(Files.exists(file));
        LspConfig reloaded = new LspConfigLoader(projectDir.toString()).load();
        assertNotNull(reloaded.server("java"));
        assertFalse(reloaded.isAutoFeedback());
        assertEquals("all", reloaded.feedbackScope());
    }
}
