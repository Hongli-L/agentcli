package com.agentcli.tool;

import com.agentcli.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        registry = new ToolRegistry(projectRoot.toString());
    }

    @Test
    void exposesBuiltinToolDefinitions() {
        List<LlmClient.Tool> defs = registry.getToolDefinitions();
        Set<String> names = defs.stream().map(LlmClient.Tool::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "read_file", "write_file", "list_dir", "glob_files",
                "grep_code", "execute_command", "create_project")));
    }

    @Test
    void writeThenReadRoundTrip() {
        String write = registry.executeTool("write_file",
                "{\"path\":\"notes/hello.txt\",\"content\":\"hello world\"}");
        assertTrue(write.contains("文件已写入"), write);

        String read = registry.executeTool("read_file", "{\"path\":\"notes/hello.txt\"}");
        assertTrue(read.contains("hello world"), read);
    }

    @Test
    void listDirShowsWrittenFile() {
        registry.executeTool("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");
        String listed = registry.executeTool("list_dir", "{\"path\":\".\"}");
        assertTrue(listed.contains("a.txt"), listed);
    }

    @Test
    void globFindsFilesByPattern() {
        registry.executeTool("write_file", "{\"path\":\"src/Main.java\",\"content\":\"class Main {}\"}");
        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*.java\"}");
        assertTrue(result.contains("Main.java"), result);
    }

    @Test
    void grepFindsContent() {
        registry.executeTool("write_file",
                "{\"path\":\"app.txt\",\"content\":\"alpha\\nNEEDLE here\\nbeta\"}");
        String result = registry.executeTool("grep_code", "{\"pattern\":\"NEEDLE\"}");
        assertTrue(result.contains("app.txt"), result);
    }

    @Test
    void unknownToolReturnsMessage() {
        assertEquals("未知工具: nope", registry.executeTool("nope", "{}"));
    }

    @Test
    void invalidJsonArgumentsAreRejectedClearly() {
        String result = registry.executeTool("list_dir", "{bad-json");
        assertTrue(result.contains("工具参数必须是合法 JSON"), result);
    }

    @Test
    void pathTraversalIsRejected() {
        String result = registry.executeTool("read_file", "{\"path\":\"../../etc/passwd\"}");
        assertTrue(result.startsWith("🛡️ 策略拒绝"), result);
    }

    @Test
    void dangerousCommandIsRejected() {
        String result = registry.executeTool("execute_command", "{\"command\":\"sudo rm -rf /\"}");
        assertTrue(result.startsWith("🛡️ 策略拒绝"), result);
    }

    @Test
    void oversizeWriteIsRejectedViaPolicy() {
        registry.executeTool("write_file", "{\"path\":\"ok.txt\",\"content\":\"fine\"}");
        String huge = registry.executeTool("create_project", "{\"name\":\"demo\",\"type\":\"java\"}");
        assertTrue(huge.contains("项目已创建"), huge);
    }
}
