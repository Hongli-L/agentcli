package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.testsupport.StubLlmClient;
import com.agentcli.common.AppConfig;
import com.agentcli.llm.LlmClient;
import com.agentcli.rag.CodeIndex;
import com.agentcli.rag.CodeRetriever;
import com.agentcli.rag.EmbeddingClient;
import com.agentcli.rag.RagCommandHandler;
import com.agentcli.rag.VectorStore;
import com.agentcli.store.SqliteStore;
import com.agentcli.tool.ToolRegistry;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.skill.SkillStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InteractiveSessionTest {

    private StubLlmClient llm;
    private ReActAgent agent;
    private InteractiveSession session;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        llm = new StubLlmClient("stub", "stub");
        ToolRegistry registry = new ToolRegistry(projectRoot.toString());
        agent = new ReActAgent(llm, registry);
        session = new InteractiveSession(agent, llm, new AppConfig());
        output = new ByteArrayOutputStream();
    }

    @Test
    void runTurnPrintsFinalAnswerWhenNotStreamed() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "done", null, List.of(), 1, 1));

        session.runTurn("hello", new PrintStream(output, true, StandardCharsets.UTF_8));

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("done"));
    }

    @Test
    void runTurnDoesNotDuplicateStreamedAnswer() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "done", null, List.of(), 1, 1));

        session.runTurn("hello", new PrintStream(output, true, StandardCharsets.UTF_8));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(text, "done"));
    }

    @Test
    void clearCommandResetsHistory() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "x", null, List.of(), 1, 1));
        session.runTurn("hi", new PrintStream(output, true, StandardCharsets.UTF_8));
        assertTrue(agent.getConversationHistory().size() > 1);

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/clear"), out));
        assertEquals(1, agent.getConversationHistory().size());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("清空"));
    }

    @Test
    void memoryStatusCommandPrintsStatus() {
        agent.getMemoryManager().storeFact("test memory");
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/memory"), out));
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("长期记忆"));
    }

    @Test
    void statusCommandPrintsContextProfile() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/status"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("会话状态"), text);
        assertTrue(text.contains("stub"), text);
    }

    @Test
    void statusCommandShowsPromptCacheModeWhenSupported() {
        // v0.17.0：/status 消费 LlmClient.promptCacheMode()，避免元数据成为无消费方的死 API
        llm.configurePromptCache(true, "moonshot-context-cache");

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/status"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("提示词缓存"), text);
        assertTrue(text.contains("moonshot-context-cache"), text);
        // 既有画像仍完整输出，缓存行是追加而非替换
        assertTrue(text.contains("会话状态"), text);
    }

    @Test
    void statusCommandShowsFriendlyTextWhenPromptCacheUnavailable() {
        // 默认 stub 走 LlmClient 接口默认值（不支持 / none）
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/status"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("提示词缓存"), text);
        assertTrue(text.contains("无（该 provider 未启用）"), text);
        assertTrue(!text.contains("none"), "none 应渲染为中文友好文案而非原样透出: " + text);
    }

    @Test
    void usageCommandPrintsTokenReport() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "ok", null, List.of(), 120, 30));
        session.runTurn("hi", new PrintStream(output, true, StandardCharsets.UTF_8));
        output.reset();

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        assertTrue(session.handleCommand(CliCommandParser.parse("/usage"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Token 用量"), text);
        assertTrue(text.contains("估算"), text);
    }

    @Test
    void sessionHelpMentionsStatusAndUsage() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        InteractiveSession.printSessionHelp(out);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("/status"), text);
        assertTrue(text.contains("/usage"), text);
    }

    @Test
    void replExitsOnExitCommand() throws Exception {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        ReplReader input = new ReplReader.BufferedReplReader(
                new java.io.BufferedReader(new StringReader("/exit\n")),
                out);
        session.run(input, out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("再见"));
    }

    @Test
    void skillCommandsRouteCorrectly(@TempDir Path projectRoot) throws Exception {
        Path skillsDir = projectRoot.resolve(".agentcli").resolve("skills");
        writeSkill(skillsDir, "alpha", "alpha desc");
        writeSkill(skillsDir, "beta", "beta desc");
        SkillStateStore store = new SkillStateStore(projectRoot.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, skillsDir, store);
        registry.reload();

        ReActAgent agent2 = new ReActAgent(llm, new ToolRegistry(projectRoot.toString()));
        InteractiveSession skillSession = new InteractiveSession(agent2, llm, new AppConfig(),
                null, registry, store);

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        skillSession.handleCommand(CliCommandParser.parse("/skill"), out);
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("alpha"), text);
        assertTrue(text.contains("beta"), text);

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill show alpha"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("body of alpha"));

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill off alpha"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("已禁用"));
        assertNull(registry.findSkill("alpha"));

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill on alpha"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("已启用"));
        assertNotNull(registry.findSkill("alpha"));

        output.reset();
        skillSession.handleCommand(CliCommandParser.parse("/skill reload"), out);
        String reloaded = output.toString(StandardCharsets.UTF_8);
        assertTrue(reloaded.contains("alpha"), reloaded);
    }

    @Test
    void skillCommandWithoutRegistryPrintsUninitialized() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        session.handleCommand(CliCommandParser.parse("/skill"), out);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("未初始化"));
    }

    // ---------------------------------------------------------------- v0.18.0 RAG 三命令派发

    @Test
    void ragCommandsAreDispatchedToHandlerAndPrinted(@TempDir Path ragRoot) throws Exception {
        List<String> calls = new java.util.ArrayList<>();
        session.setRagCommandHandler(recordingHandler(ragRoot, calls));
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

        assertTrue(session.handleCommand(CliCommandParser.parse("/index"), out));
        assertTrue(session.handleCommand(CliCommandParser.parse("/search 用户登录"), out));
        assertTrue(session.handleCommand(CliCommandParser.parse("/graph ReactAgent"), out));

        assertEquals(List.of("index:null", "search:用户登录", "graph:ReactAgent"), calls);
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[index-done]"), text);
        assertTrue(text.contains("[search-done]"), text);
        assertTrue(text.contains("[graph-done]"), text);
    }

    @Test
    void indexProgressIsStreamedToConsole(@TempDir Path ragRoot) throws Exception {
        session.setRagCommandHandler(recordingHandler(ragRoot, new java.util.ArrayList<>()));
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

        session.handleCommand(CliCommandParser.parse("/index"), out);

        // 索引耗时长，进度必须实时可见而不是攒到最后一次性吐出
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[progress]"), text);
        assertTrue(text.indexOf("[progress]") < text.indexOf("[index-done]"), text);
    }

    @Test
    void searchWithoutArgumentPrintsUsageHint(@TempDir Path ragRoot) throws Exception {
        session.setRagCommandHandler(realHandler(ragRoot));
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

        assertTrue(session.handleCommand(CliCommandParser.parse("/search"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("请提供检索关键词"), text);
    }

    @Test
    void graphWithoutArgumentPrintsUsageHint(@TempDir Path ragRoot) throws Exception {
        session.setRagCommandHandler(realHandler(ragRoot));
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

        assertTrue(session.handleCommand(CliCommandParser.parse("/graph"), out));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("请提供类名"), text);
    }

    @Test
    void sessionHelpMentionsRagCommands() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        InteractiveSession.printSessionHelp(out);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("/index"), text);
        assertTrue(text.contains("/search"), text);
        assertTrue(text.contains("/graph"), text);
    }

    /** 记录派发参数的门面桩：三个方法全部覆写，底层依赖不会被触碰。 */
    private RagCommandHandler recordingHandler(Path root, List<String> calls) throws Exception {
        return new RagCommandHandler(newCodeIndex(root), newRetriever(root), root) {
            @Override
            public String handleIndex(String payload, CodeIndex.ProgressListener listener) {
                calls.add("index:" + payload);
                listener.onProgress("[progress] 开始索引");
                return "[index-done]";
            }

            @Override
            public String handleSearch(String payload) {
                calls.add("search:" + payload);
                return "[search-done]";
            }

            @Override
            public String handleGraph(String payload) {
                calls.add("graph:" + payload);
                return "[graph-done]";
            }
        };
    }

    /** 未打桩的真实门面，用于验证空参数用法提示确实由门面产出。 */
    private RagCommandHandler realHandler(Path root) throws Exception {
        return new RagCommandHandler(newCodeIndex(root), newRetriever(root), root);
    }

    private CodeIndex newCodeIndex(Path root) throws Exception {
        return new CodeIndex(newVectorStore(root), EmbeddingClient.disabled("测试未配置 embedding"));
    }

    private CodeRetriever newRetriever(Path root) throws Exception {
        return new CodeRetriever(newVectorStore(root),
                EmbeddingClient.disabled("测试未配置 embedding"), null);
    }

    private VectorStore newVectorStore(Path root) throws Exception {
        return new VectorStore(SqliteStore.open(root.resolve("rag-session-test.db")));
    }

    private void writeSkill(Path root, String name, String description) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\nbody of " + name + "\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
