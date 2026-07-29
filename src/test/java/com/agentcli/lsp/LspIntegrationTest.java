package com.agentcli.lsp;

import com.agentcli.agent.ReActAgent;
import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.testutil.LspTestSupport;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.tool.ToolRegistry;
import com.agentcli.tool.builtin.WriteFileTool;
import com.agentcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LSP-04 核心验收：AI 改完文件后自动拉取诊断并回灌 ReAct 循环，
 * 使其在 ≤3 轮内自我修正、最终无 error。
 */
@Tag("lsp")
class LspIntegrationTest {

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
    @Tag("lsp")
    void selfCorrectsWithinThreeRounds() throws Exception {
        Path projectDir = Path.of(System.getProperty("java.io.tmpdir"), "lsp-integ-" + System.nanoTime());
        Files.createDirectories(projectDir);
        Path appJava = projectDir.resolve("App.java");

        LspConfig cfg = new LspConfig();
        cfg.setServer("java", LspTestSupport.fakeJavaServer(
                projectDir.toString(), LspTestSupport.fooErrorDiagnostics()));
        cfg.setProjectDir(projectDir.toString());
        cfg.setAutoFeedback(true);

        LspServerManager mgr = new LspServerManager(cfg);
        mgr.start("java");
        try {
            assertTrue(mgr.status("java") == LspServer.Status.READY, "服务器应启动就绪");

            LspContextInjector inj = new LspContextInjector(
                    mgr, cfg, new LspLanguageDetector(), new LspDiagnosticsFormatter());

            // 脚本化：第 1 轮写入含未定义符号 foo 的代码；
            // 第 2 轮诊断回灌后修正；第 3 轮给出最终答复。
            StubLlmClient llm = new StubLlmClient("stub", "stub");
            String broken = "public class App {\n    void m() {\n        int x = foo;\n    }\n}\n";
            String fixed = "public class App {\n    void m() {\n        int x = 0;\n    }\n}\n";
            llm.enqueue(new LlmClient.ChatResponse("assistant", "", null,
                    List.of(new LlmClient.ToolCall("c1",
                            new LlmClient.ToolCall.Function("write_file", writeArgs(appJava, broken)))),
                    1, 1, 0));
            llm.enqueue(new LlmClient.ChatResponse("assistant", "", null,
                    List.of(new LlmClient.ToolCall("c2",
                            new LlmClient.ToolCall.Function("write_file", writeArgs(appJava, fixed)))),
                    1, 1, 0));
            llm.enqueue(new LlmClient.ChatResponse("assistant", "已创建并修正 App.java。", null,
                    List.of(), 1, 1, 0));

            ReActAgent agent = new ReActAgent(llm, new ToolRegistry(projectDir.toString()));
            agent.setLspInjector(inj);

            String result = agent.run("创建 App.java");
            assertNotNull(result, "应返回最终答复");

            String finalContent = Files.readString(appJava);
            assertFalse(finalContent.contains("foo"),
                    "最终代码不应含未定义符号 foo：" + finalContent);
            assertTrue(llm.chatCalls() <= 3,
                    "应在 ≤3 轮内收敛，实际 " + llm.chatCalls() + " 轮");
        } finally {
            mgr.stopAll();
        }
    }
}
