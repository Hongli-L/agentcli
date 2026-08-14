package com.agentcli.tool.builtin;

import com.agentcli.common.PolicyException;
import com.agentcli.common.SandboxPolicy;
import com.agentcli.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteFileToolTest {

    @TempDir
    Path tempDir;

    private WriteFileTool tool(SandboxPolicy policy) {
        return new WriteFileTool(new ToolContext(tempDir.toString(), policy));
    }

    @Test
    void securePolicyRequiresConfirmToOverwrite() {
        WriteFileTool tool = tool(SandboxPolicy.secure());
        Path target = tempDir.resolve("note.txt");
        assertTrue(tool.execute(Map.of("path", target.toString(), "content", "v1")).startsWith("文件已写入"));

        // 覆盖但未确认 → 二次确认拒绝
        assertThrows(PolicyException.class,
                () -> tool.execute(Map.of("path", target.toString(), "content", "v2")));

        // 显式确认 → 允许覆盖
        String result = tool.execute(Map.of("path", target.toString(), "content", "v2", "confirm", "true"));
        assertTrue(result.startsWith("文件已写入"));
        assertEquals("v2", readString(target));
    }

    @Test
    void legacyPolicyAllowsOverwriteWithoutConfirm() {
        WriteFileTool tool = tool(SandboxPolicy.legacy());
        Path target = tempDir.resolve("note.txt");
        tool.execute(Map.of("path", target.toString(), "content", "v1"));
        String result = tool.execute(Map.of("path", target.toString(), "content", "v2"));
        assertTrue(result.startsWith("文件已写入"));
        assertEquals("v2", readString(target));
    }

    @Test
    void securePolicyBlocksWritesIntoProtectedDir() {
        WriteFileTool tool = tool(SandboxPolicy.secure());
        Path gitConfig = tempDir.resolve(".git").resolve("config");
        assertThrows(PolicyException.class,
                () -> tool.execute(Map.of("path", gitConfig.toString(), "content", "evil")));
    }

    @Test
    void rejectsOversizedContent() {
        WriteFileTool tool = tool(SandboxPolicy.legacy());
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6 * 1024 * 1024; i++) {
            big.append('a');
        }
        assertThrows(PolicyException.class,
                () -> tool.execute(Map.of("path", tempDir.resolve("big.txt").toString(),
                        "content", big.toString())));
    }

    private String readString(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
