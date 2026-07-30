package com.agentcli.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplHistoryTest {

    @Test
    void skipsEmptyLine() {
        ReplHistory h = new ReplHistory();
        assertTrue(h.shouldSkip(""));
        assertTrue(h.shouldSkip("   "));
        assertTrue(h.shouldSkip("\n"));
    }

    @Test
    void skipsSecretLines() {
        assertTrue(new ReplHistory().shouldSkip("api_key=sk-1234567890"));
        assertTrue(new ReplHistory().shouldSkip("token xyzabc"));
        assertTrue(new ReplHistory().shouldSkip("Bearer abc.def.ghi"));
        assertTrue(new ReplHistory().shouldSkip("set password=secret123"));
        assertTrue(new ReplHistory().shouldSkip("authorization: Basic xxx"));
    }

    @Test
    void skipsOverlongLine() {
        String longLine = "x".repeat(8001);
        assertTrue(new ReplHistory().shouldSkip(longLine));
    }

    @Test
    void skipsLongBase64() {
        String b64 = "A".repeat(240);
        assertTrue(new ReplHistory().shouldSkip(b64));
    }

    @Test
    void keepsNormalLine() {
        assertFalse(new ReplHistory().shouldSkip("/plan"));
        assertFalse(new ReplHistory().shouldSkip("explain this function to me"));
        assertFalse(new ReplHistory().shouldSkip("/skill on my-skill"));
    }

    @Test
    void constructorDoesNotCreateHistoryFile() {
        // 仅构造不应创建 ~/.agentcli 目录或 history 文件（避免非 TTY 副作用）
        Path historyPath = ReplHistory.DEFAULT_PATH;
        assertFalse(Files.exists(historyPath), "构造 ReplHistory 不应创建历史文件");
    }
}
