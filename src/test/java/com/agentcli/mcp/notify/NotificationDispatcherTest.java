package com.agentcli.mcp.notify;

import com.agentcli.mcp.testutil.FakeMcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link NotificationDispatcher} 解析三类通知（window/logMessage、notifications/message、
 * notifications/progress）并正确映射 level / message / progress，且对未知 method 与解析异常静默忽略。
 */
public class NotificationDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class CapturingPresenter implements NotificationPresenter {
        final List<NotificationRecord> records = new ArrayList<>();

        @Override
        public void show(NotificationRecord record) {
            records.add(record);
        }
    }

    private static JsonNode params(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void mapsWindowLogMessageToLevelAndMessage() {
        CapturingPresenter presenter = new CapturingPresenter();
        NotificationDispatcher dispatcher = new NotificationDispatcher("test", presenter);
        FakeMcpTransport transport = new FakeMcpTransport(Map.of());
        transport.onReceive(dispatcher);
        transport.pushNotification("window/logMessage",
                params("{\"level\":\"warning\",\"message\":\"disk low\"}"));
        assertEquals(1, presenter.records.size());
        NotificationRecord r = presenter.records.get(0);
        assertEquals(NotificationLevel.WARNING, r.level());
        assertEquals("disk low", r.message());
        assertEquals("test", r.serverName());
    }

    @Test
    void mapsNotificationsMessageDataToString() {
        CapturingPresenter presenter = new CapturingPresenter();
        NotificationDispatcher dispatcher = new NotificationDispatcher("srv", presenter);
        FakeMcpTransport transport = new FakeMcpTransport(Map.of());
        transport.onReceive(dispatcher);
        transport.pushNotification("notifications/message",
                params("{\"level\":\"info\",\"data\":\"payload-x\"}"));
        assertEquals(1, presenter.records.size());
        assertEquals(NotificationLevel.INFO, presenter.records.get(0).level());
        assertEquals("payload-x", presenter.records.get(0).message());
    }

    @Test
    void mapsNotificationsProgress() {
        CapturingPresenter presenter = new CapturingPresenter();
        NotificationDispatcher dispatcher = new NotificationDispatcher("srv", presenter);
        FakeMcpTransport transport = new FakeMcpTransport(Map.of());
        transport.onReceive(dispatcher);
        transport.pushNotification("notifications/progress",
                params("{\"progress\":5,\"total\":10}"));
        assertEquals(1, presenter.records.size());
        NotificationRecord r = presenter.records.get(0);
        assertEquals(NotificationLevel.PROGRESS, r.level());
        assertEquals(5L, r.progress());
        assertEquals(10L, r.total());
        assertEquals("5/10", r.message());
    }

    @Test
    void ignoresUnknownMethod() {
        CapturingPresenter presenter = new CapturingPresenter();
        NotificationDispatcher dispatcher = new NotificationDispatcher("srv", presenter);
        FakeMcpTransport transport = new FakeMcpTransport(Map.of());
        transport.onReceive(dispatcher);
        transport.pushNotification("some/other", params("{}"));
        assertTrue(presenter.records.isEmpty());
    }

    @Test
    void silentlyIgnoresMalformedNotification() {
        CapturingPresenter presenter = new CapturingPresenter();
        NotificationDispatcher dispatcher = new NotificationDispatcher("srv", presenter);
        // 非对象 / null 通知：parse 返回 null 或 early return，不抛、不记录
        dispatcher.accept(params("\"not-an-object\""));
        dispatcher.accept(null);
        assertTrue(presenter.records.isEmpty());
    }
}
