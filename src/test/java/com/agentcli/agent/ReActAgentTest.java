package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolExecutionResult;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentTest {

    private StubLlmClient llm;
    private ToolRegistry toolRegistry;
    private ReActAgent agent;

    @BeforeEach
    void setUp(@TempDir Path projectRoot) {
        llm = new StubLlmClient("stub-model", "stub");
        toolRegistry = new ToolRegistry(projectRoot.toString());
        agent = new ReActAgent(llm, toolRegistry);
    }

    @Test
    void directAnswerWithoutTools() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "hello from model", null, List.of(), 10, 5));

        String answer = agent.run("hi");

        assertEquals("hello from model", answer);
        assertEquals(3, agent.getConversationHistory().size()); // system + user + assistant
    }

    @Test
    void toolCallThenFinalAnswer() {
        llm.enqueue(new LlmClient.ChatResponse(
                "assistant", "", null,
                List.of(new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("write_file",
                                "{\"path\":\"note.txt\",\"content\":\"saved\"}"))),
                20, 10));
        llm.enqueue(new LlmClient.ChatResponse("assistant", "file written", null, List.of(), 15, 8));

        String answer = agent.run("save a note");

        assertEquals("file written", answer);
        assertTrue(toolRegistry.executeTool("read_file", "{\"path\":\"note.txt\"}").contains("saved"));
        assertEquals(5, agent.getConversationHistory().size()); // system,user,assistant+tools,tool,assistant
    }

    @Test
    void parallelToolCallsAreExecuted() {
        llm.enqueue(new LlmClient.ChatResponse(
                "assistant", "", null,
                List.of(
                        new LlmClient.ToolCall("c1", new LlmClient.ToolCall.Function("write_file",
                                "{\"path\":\"a.txt\",\"content\":\"A\"}")),
                        new LlmClient.ToolCall("c2", new LlmClient.ToolCall.Function("write_file",
                                "{\"path\":\"b.txt\",\"content\":\"B\"}"))
                ),
                30, 12));
        llm.enqueue(new LlmClient.ChatResponse("assistant", "done", null, List.of(), 10, 5));

        agent.run("write two files");

        assertTrue(toolRegistry.executeTool("read_file", "{\"path\":\"a.txt\"}").contains("A"));
        assertTrue(toolRegistry.executeTool("read_file", "{\"path\":\"b.txt\"}").contains("B"));
    }

    @Test
    void stagnationStopsLoop() {
        llm.enqueue(toolCallResponse("read_file", "{\"path\":\"x.txt\"}"));
        llm.enqueue(toolCallResponse("read_file", "{\"path\":\"x.txt\"}"));
        llm.enqueue(toolCallResponse("read_file", "{\"path\":\"x.txt\"}"));

        String answer = agent.run("loop");

        assertTrue(answer.startsWith("❌"), answer);
        assertTrue(answer.contains("死循环"), answer);
    }

    @Test
    void llmFailureReturnsErrorMessage() {
        llm.enqueueFailure(new IOException("network down"));

        String answer = agent.run("fail please");

        assertTrue(answer.contains("调用 LLM 失败"), answer);
        assertTrue(answer.contains("network down"), answer);
    }

    @Test
    void listenerReceivesToolEvents() {
        RecordingListener recording = new RecordingListener();
        agent.setListener(recording);

        llm.enqueue(toolCallResponse("list_dir", "{\"path\":\".\"}"));
        llm.enqueue(new LlmClient.ChatResponse("assistant", "listed", null, List.of(), 5, 3));

        agent.run("list files");

        assertTrue(recording.iterations.contains(1));
        assertEquals(1, recording.toolCallBatches.size());
        assertEquals("list_dir", recording.toolCallBatches.get(0).get(0).function().name());
        assertEquals(1, recording.toolResults.size());
        assertEquals("list_dir", recording.toolResults.get(0).name());
    }

    @Test
    void clearHistoryResetsConversation() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "first", null, List.of(), 1, 1));
        agent.run("one");
        agent.clearHistory();
        assertEquals(1, agent.getConversationHistory().size());
        assertEquals("system", agent.getConversationHistory().get(0).role());
    }

    private static LlmClient.ChatResponse toolCallResponse(String toolName, String args) {
        return new LlmClient.ChatResponse(
                "assistant", "", null,
                List.of(new LlmClient.ToolCall("tc", new LlmClient.ToolCall.Function(toolName, args))),
                5, 5);
    }

    private static final class RecordingListener implements AgentRunListener {
        private final List<Integer> iterations = new CopyOnWriteArrayList<>();
        private final List<List<LlmClient.ToolCall>> toolCallBatches = new CopyOnWriteArrayList<>();
        private final List<ToolExecutionResult> toolResults = new CopyOnWriteArrayList<>();

        @Override
        public void onIterationStart(int iteration) {
            iterations.add(iteration);
        }

        @Override
        public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
            toolCallBatches.add(toolCalls);
        }

        @Override
        public void onToolResult(ToolExecutionResult result) {
            toolResults.add(result);
        }
    }

    static final class StubLlmClient implements LlmClient {
        private final String modelName;
        private final String providerName;
        private final List<Object> scripted = new ArrayList<>();
        private int chatCalls;

        StubLlmClient(String modelName, String providerName) {
            this.modelName = modelName;
            this.providerName = providerName;
        }

        void enqueue(LlmClient.ChatResponse response) {
            scripted.add(response);
        }

        void enqueueFailure(IOException error) {
            scripted.add(error);
        }

        int chatCalls() {
            return chatCalls;
        }

        @Override
        public LlmClient.ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public LlmClient.ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            chatCalls++;
            if (scripted.isEmpty()) {
                throw new IOException("no scripted response");
            }
            Object next = scripted.remove(0);
            if (next instanceof IOException io) {
                throw io;
            }
            LlmClient.ChatResponse response = (LlmClient.ChatResponse) next;
            if (response.reasoningContent() != null && listener != null) {
                listener.onReasoningDelta(response.reasoningContent());
            }
            if (response.content() != null && listener != null) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        @Override
        public String getModelName() {
            return modelName;
        }

        @Override
        public String getProviderName() {
            return providerName;
        }
    }
}
