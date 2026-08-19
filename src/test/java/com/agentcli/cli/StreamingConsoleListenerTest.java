package com.agentcli.cli;

import com.agentcli.agent.TurnUsage;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingConsoleListenerTest {

    @Test
    void streamsContentAndTools() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamingConsoleListener listener = new StreamingConsoleListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

        listener.onContentDelta("Hello");
        assertTrue(listener.hasStreamedContent());
        listener.onToolCalls(List.of(new LlmClient.ToolCall("id1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"))));
        listener.onToolResult(new ToolExecutionResult("id1", "read_file", "{}", "ok", 12, false));
        listener.finishTurn();

        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Hello"));
        assertTrue(out.contains("tool read_file"));
        assertTrue(out.contains("ok read_file"));
    }

    @Test
    void reasoningPrintedBeforeContent() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamingConsoleListener listener = new StreamingConsoleListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

        listener.onReasoningDelta("think");
        listener.onContentDelta("answer");
        listener.finishTurn();

        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.indexOf("Thinking") < out.indexOf("answer"));
    }

    @Test
    void onTurnSummaryPrintsBelowAnswerAtFinish() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamingConsoleListener listener = new StreamingConsoleListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

        listener.onContentDelta("the answer");
        listener.onTurnSummary(new TurnUsage("glm", "glm-4", 100, 50, 0, 800));
        listener.finishTurn();

        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("本回合"), out);
        assertTrue(out.contains("100"), out);
        assertTrue(out.contains("⏱"), out);
        // 小结必须出现在回答正文之后，而不是穿插在流式输出里
        assertTrue(out.indexOf("the answer") < out.indexOf("本回合"), out);
    }

    @Test
    void turnSummaryOmittedWithoutOnTurnSummaryCall() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamingConsoleListener listener = new StreamingConsoleListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

        listener.onContentDelta("plain answer");
        listener.finishTurn();

        String out = buffer.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains("本回合"), out);
    }
}
