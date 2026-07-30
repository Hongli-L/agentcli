package com.agentcli.cli;

import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.History;

import com.agentcli.common.AgentCliHighlighter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplEnhancementsTest {

    @Test
    void holderCarriesAllThreeComponents() {
        Completer c = (r, l, cands) -> { };
        Highlighter h = new AgentCliHighlighter(false);
        History hist = new ReplHistory();
        ReplEnhancements enh = new ReplEnhancements(c, h, hist);
        assertEquals(c, enh.completer);
        assertEquals(h, enh.highlighter);
        assertEquals(hist, enh.history);
    }

    @Test
    void holderAllowsNullComponents() {
        ReplEnhancements enh = new ReplEnhancements(null, null, null);
        assertNull(enh.completer);
        assertNull(enh.highlighter);
        assertNull(enh.history);
    }

    @Test
    void nonTtyUsesBufferedReaderAndCreatesNoHistory() throws Exception {
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true);
        // 无系统控制台环境下 isJlineAvailable() 为假（非 TTY 门控）
        assertFalse(ReplReader.isJlineAvailable());
        ReplReader reader = ReplReader.create(StandardCharsets.UTF_8, out, null);
        assertEquals("BufferedReplReader", reader.getClass().getSimpleName());
        Path historyPath = ReplHistory.DEFAULT_PATH;
        assertFalse(Files.exists(historyPath), "非 TTY 不应创建 ~/.agentcli/history");
    }
}
