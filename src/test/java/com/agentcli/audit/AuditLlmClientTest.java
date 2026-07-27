package com.agentcli.audit;

import com.agentcli.llm.LlmClient;
import com.agentcli.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLlmClientTest {

    private static final LlmClient.ChatResponse RESPONSE =
            new LlmClient.ChatResponse("assistant", "hello", null, null, 12, 34, 0);

    private LlmClient fakeDelegate() {
        return new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return RESPONSE;
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                return RESPONSE;
            }

            @Override
            public String getModelName() {
                return "test-model";
            }

            @Override
            public String getProviderName() {
                return "test";
            }
        };
    }

    @Test
    void recordsEachChatCallAndForwardsResponse() throws Exception {
        Path dir = Files.createTempDirectory("agentcli-audit-llm-test");
        SqliteStore store = SqliteStore.open(dir.resolve("agentcli-test.db"));
        AuditLog auditLog = new AuditLog(store);

        AuditLlmClient client = new AuditLlmClient(fakeDelegate(), auditLog);
        LlmClient.ChatResponse r1 = client.chat(List.of(LlmClient.Message.user("hi")), null);
        LlmClient.ChatResponse r2 = client.chat(List.of(LlmClient.Message.user("again")), null, LlmClient.StreamListener.NO_OP);

        assertEquals("hello", r1.content());
        assertEquals("hello", r2.content());
        assertEquals("test-model", client.getModelName());
        assertEquals("test", client.getProviderName());

        var entries = auditLog.query(10);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> "llm".equals(e.kind())));
        assertEquals(46, entries.get(0).tokens()); // 12 + 34
    }
}
