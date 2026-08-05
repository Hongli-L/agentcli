package com.agentcli.cli;

import com.agentcli.agent.ReActAgent;
import com.agentcli.agent.orchestration.OrchestrationConfig;
import com.agentcli.testsupport.ConcurrentStubLlmClient;
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

    // ---------------------------------------------------------------- v0.20.0 编排三命令集成

    /** 含「然后」，绕开 {@code Planner#isSimpleGoal} 的单任务短路，必定走 LLM 规划路径。 */
    private static final String TEAM_GOAL = "读取项目配置，然后输出一份差异分析报告";

    /** 两步链式计划：{@code task_2} 依赖 {@code task_1}，串行执行顺序确定。 */
    private static final String CHAIN_PLAN = """
            {"summary":"两步计划","tasks":[
              {"id":"1","description":"读取配置文件","type":"FILE_READ","dependencies":[]},
              {"id":"2","description":"分析配置差异","type":"ANALYSIS","dependencies":["1"]}
            ]}""";

    @Test
    void teamCommandRunsWholeOrchestrationAndPrintsAggregateReport(@TempDir Path teamRoot) {
        // 关掉 reviewer 并把并发压到 1：REPL 集成测试只验证「命令→编排→报告」这条链路是否接通，
        // 并发行为与复核解析各有专门的单测覆盖，这里不重复也不引入调度不确定性。
        ConcurrentStubLlmClient stub = new ConcurrentStubLlmClient("stub", "stub")
                .withPlan(CHAIN_PLAN)
                .withWorkerAnswer("task_1", "配置读取完毕：端口 8080")
                .withWorkerAnswer("task_2", "分析完毕：无异常配置项");
        InteractiveSession teamSession = newOrchestrationSession(stub, teamRoot);
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

        try {
            // 空参数先给用法提示，且绝不触发任何 LLM 调用
            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/team"), out));
            String usage = output.toString(StandardCharsets.UTF_8);
            assertTrue(usage.contains("用法: /team"), usage);
            assertTrue(usage.contains("/plan"), "用法提示需说明与 /plan 的边界: " + usage);
            assertEquals(0, stub.chatCalls(), "空目标不应产生任何 LLM 调用");

            output.reset();
            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/team " + TEAM_GOAL), out));

            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("完成 2/2"), text);
            assertTrue(text.contains("配置读取完毕：端口 8080"), text);
            assertTrue(text.contains("分析完毕：无异常配置项"), text);
            assertEquals(1, stub.plannerCalls(), "规划只调用一次");
            assertEquals(2, stub.workerCalls());
            assertEquals(0, stub.reviewerCalls(), "reviewerEnabled=false 时不应多花一次 LLM 调用");
            assertEquals(1, stub.peakConcurrency(), "maxParallel=1 必须严格串行");
            assertTrue(!text.contains("未成功"), "全部成功时不应出现降级提示: " + text);
        } finally {
            teamSession.closeTaskExecutor();
        }
    }

    @Test
    void tasksAndCancelReflectExecutorStateBeforeAndAfterOrchestration(@TempDir Path teamRoot) {
        ConcurrentStubLlmClient stub = new ConcurrentStubLlmClient("stub", "stub")
                .withPlan(CHAIN_PLAN)
                .withDefaultWorkerAnswer("子任务完成");
        InteractiveSession teamSession = newOrchestrationSession(stub, teamRoot);
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

        try {
            // ① 从未编排过：执行器根本没被创建，两条命令都走空态而不是抛 NPE
            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/tasks"), out));
            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/cancel"), out));
            String empty = output.toString(StandardCharsets.UTF_8);
            assertEquals(2, countOccurrences(empty, "暂无编排任务。"), empty);

            // ② 跑一轮编排后，快照必须留存本轮全部任务（含已完成的），供用户回看
            output.reset();
            teamSession.handleCommand(CliCommandParser.parse("/team " + TEAM_GOAL), out);
            output.reset();

            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/tasks"), out));
            String listed = output.toString(StandardCharsets.UTF_8);
            assertTrue(listed.contains("编排任务 (2 个):"), listed);
            assertTrue(listed.contains("task_1"), listed);
            assertTrue(listed.contains("task_2"), listed);
            assertTrue(listed.contains("DONE"), listed);

            // ③ 任务已全部终态：无参 /cancel 不该谎报「已取消 N 个」
            output.reset();
            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/cancel"), out));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("没有正在运行的任务。"),
                    output.toString(StandardCharsets.UTF_8));

            // ④ 指名取消一个不存在的 id：明确回报未命中，而不是静默成功
            output.reset();
            assertTrue(teamSession.handleCommand(CliCommandParser.parse("/cancel task_404"), out));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("未找到运行中的任务: task_404"),
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            teamSession.closeTaskExecutor();
        }
    }

    @Test
    void sessionHelpMentionsOrchestrationCommands() {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        InteractiveSession.printSessionHelp(out);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("/team"), text);
        assertTrue(text.contains("/cancel"), text);
        assertTrue(text.contains("/tasks"), text);
    }

    /**
     * 造一个以并发桩为后端的会话：串行 + 关闭 reviewer，让 REPL 集成断言完全确定。
     *
     * <p>注意会话与编排器共享同一个 {@code LlmClient}——这正是生产装配的形态，
     * 用桩替换后 planner / worker / reviewer 三种调用由桩按内容自行路由。
     */
    private InteractiveSession newOrchestrationSession(ConcurrentStubLlmClient stub, Path root) {
        ReActAgent orchestrationAgent = new ReActAgent(stub, new ToolRegistry(root.toString()));
        InteractiveSession orchestrationSession =
                new InteractiveSession(orchestrationAgent, stub, new AppConfig());
        orchestrationSession.setOrchestrationConfig(
                new OrchestrationConfig(1, 1, 30_000L, 60_000L, false, 200));
        return orchestrationSession;
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
