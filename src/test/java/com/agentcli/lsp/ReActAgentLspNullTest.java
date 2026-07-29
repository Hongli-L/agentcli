package com.agentcli.lsp;

import com.agentcli.agent.ReActAgent;
import com.agentcli.llm.LlmClient;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ReActAgent} 在不注入 LSP {@code lspInjector}（保持 null）时
 * 仍能正常完成 ReAct 循环、不抛 NPE、不干预主流程。
 */
class ReActAgentLspNullTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String writeArgs(Path path, String content) {
        try {
            var obj = MAPPER.createObjectNode();
            obj.put("path", path.toString());
            obj.put("content", content);
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Test
    void nullInjectorKeepsAgentWorking() throws Exception {
        Path projectDir = Path.of(System.getProperty("java.io.tmpdir"), "lsp-null-" + System.nanoTime());
        Files.createDirectories(projectDir);
        Path appJava = projectDir.resolve("App.java");

        StubLlmClient llm = new StubLlmClient("stub", "stub");
        String content = "public class App {}";
        llm.enqueue(new LlmClient.ChatResponse("assistant", "", null,
                List.of(new LlmClient.ToolCall("c1",
                        new LlmClient.ToolCall.Function("write_file", writeArgs(appJava, content)))),
                1, 1, 0));
        llm.enqueue(new LlmClient.ChatResponse("assistant", "已创建 App.java。", null,
                List.of(), 1, 1, 0));

        ReActAgent agent = new ReActAgent(llm, new ToolRegistry(projectDir.toString()));
        // 关键：不调用 setLspInjector，保持 lspInjector == null
        String result = agent.run("创建 App.java");

        assertNotNull(result, "无 LSP 注入时也应正常返回最终答复");
        assertTrue(Files.readString(appJava).contains("App"),
                "文件应被正常写入：" + appJava);
        assertTrue(llm.chatCalls() <= 2,
                "应在 ≤2 轮内收敛，实际 " + llm.chatCalls() + " 轮");
    }
}
