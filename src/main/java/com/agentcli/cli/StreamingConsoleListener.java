package com.agentcli.cli;

import com.agentcli.agent.AgentRunListener;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolExecutionResult;

import java.io.PrintStream;
import java.util.List;

/**
 * 把 Agent 流式增量与工具事件渲染到终端。
 *
 * reasoning / content 增量直接打印；工具调用显示名称与耗时摘要。
 * CLI 层在回合结束后调用 {@link #finishTurn()} 补换行并重置状态。
 */
public class StreamingConsoleListener implements AgentRunListener {

    private final PrintStream out;
    private boolean reasoningOpen;
    private boolean contentStreamed;
    private final StringBuilder contentBuffer = new StringBuilder();

    public StreamingConsoleListener(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    public boolean hasStreamedContent() {
        return contentStreamed;
    }

    public void finishTurn() {
        if (reasoningOpen || contentStreamed) {
            out.println();
        }
        reasoningOpen = false;
        contentStreamed = false;
        contentBuffer.setLength(0);
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!reasoningOpen) {
            out.println();
            out.println("Thinking...");
            reasoningOpen = true;
        }
        out.print(delta);
        out.flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        closeReasoningLine();
        contentStreamed = true;
        out.print(delta);
        contentBuffer.append(delta);
    }

    @Override
    public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
        closeReasoningLine();
        if (contentStreamed) {
            out.println();
            contentStreamed = false;
        }
        if (toolCalls == null) {
            return;
        }
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String name = toolCall.function().name();
            String args = compactArgs(toolCall.function().arguments());
            out.println("> tool " + name + (args.isEmpty() ? "" : " " + args));
        }
    }

    @Override
    public void onToolResult(ToolExecutionResult result) {
        if (result == null) {
            return;
        }
        if (result.timedOut()) {
            out.println("  (timeout) " + result.name());
            return;
        }
        String text = result.result() == null ? "" : result.result();
        if (text.startsWith("🛡️") || text.startsWith("工具执行失败")) {
            out.println("  (!) " + result.name());
            return;
        }
        out.println("  ok " + result.name() + " (" + result.elapsedMillis() + "ms)");
    }

    private void closeReasoningLine() {
        if (reasoningOpen) {
            out.println();
            reasoningOpen = false;
        }
    }

    private static String compactArgs(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String compact = json.replaceAll("\\s+", " ").trim();
        return compact.length() > 80 ? compact.substring(0, 77) + "..." : compact;
    }
}
