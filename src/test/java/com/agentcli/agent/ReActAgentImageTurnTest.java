package com.agentcli.agent;

import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentImageTurnTest {

    /** 录制每次 chat 收到的消息，用于断言「图片仅首轮发送、历史落盘转 text-only」。 */
    static final class CapturingLlmClient implements LlmClient {
        private final List<List<Message>> recorded = new ArrayList<>();
        private final List<ChatResponse> scripted = new ArrayList<>();

        void enqueue(ChatResponse r) {
            scripted.add(r);
        }

        List<List<Message>> recorded() {
            return recorded;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            recorded.add(new ArrayList<>(messages));
            if (scripted.isEmpty()) {
                throw new IOException("no scripted response");
            }
            return scripted.remove(0);
        }

        @Override
        public String getModelName() {
            return "cap-model";
        }

        @Override
        public String getProviderName() {
            return "cap";
        }
    }

    @TempDir
    Path projectRoot;
    private CapturingLlmClient llm;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        System.setProperty("AGENTCLI_MEMORY_DIR", projectRoot.resolve(".memory-test").toString());
        llm = new CapturingLlmClient();
        agent = new ReActAgent(llm, new ToolRegistry(projectRoot.toString()));
    }

    @Test
    void imageSentOnlyOnFirstCallHistoryStaysTextOnly() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "我看清了", null, List.of(), 12, 6));

        LlmClient.Message userMsg = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("这是什么图"),
                LlmClient.ContentPart.imageBase64("AAAA", "image/png")));

        String answer = agent.run(userMsg);

        assertEquals("我看清了", answer);
        // 首轮 chat 的最后一个消息必须携带图片部件
        List<LlmClient.Message> firstCall = llm.recorded().get(0);
        assertTrue(firstCall.get(firstCall.size() - 1).hasImageParts(),
                "首轮应携带图片部件");

        // 落盘历史里的用户消息必须是 text-only（无图片），避免后续轮重复发送 base64
        LlmClient.Message historyUser = agent.getConversationHistory().get(1);
        assertFalse(historyUser.hasImageParts(), "历史中的用户消息应为纯文本");
        assertEquals(null, historyUser.parts());
    }

    @Test
    void plainTextRunBehavesAsBefore() {
        llm.enqueue(new LlmClient.ChatResponse("assistant", "ok", null, List.of(), 5, 2));
        String answer = agent.run("hi");
        assertEquals("ok", answer);
        assertEquals(3, agent.getConversationHistory().size());
        assertFalse(agent.getConversationHistory().get(1).hasImageParts());
    }
}
