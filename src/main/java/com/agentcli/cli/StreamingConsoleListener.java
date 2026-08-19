package com.agentcli.cli;

import com.agentcli.agent.AgentRunListener;
import com.agentcli.agent.TurnUsage;
import com.agentcli.common.AnsiStyle;
import com.agentcli.common.TerminalMarkdownRenderer;
import com.agentcli.context.TokenUsageFormatter;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolExecutionResult;

import java.io.PrintStream;
import java.util.List;

/**
 * 把 Agent 流式增量与工具事件渲染到终端。
 *
 * reasoning 使用弱化样式；正文经 {@link TerminalMarkdownRenderer} 做轻量 Markdown 渲染。
 */
public class StreamingConsoleListener implements AgentRunListener {

    private static final String REASONING_PREFIX = "\u001B[3m\u001B[90m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final PrintStream out;
    private final TerminalMarkdownRenderer markdownRenderer;
    private boolean reasoningOpen;
    private boolean contentStreamed;
    private TurnUsage lastTurnUsage;

    public StreamingConsoleListener(PrintStream out) {
        this.out = out == null ? System.out : out;
        this.markdownRenderer = new TerminalMarkdownRenderer(this.out);
    }

    public boolean hasStreamedContent() {
        return contentStreamed;
    }

    public void finishTurn() {
        if (contentStreamed) {
            markdownRenderer.finish();
        }
        if (reasoningOpen) {
            if (AnsiStyle.isEnabled()) {
                out.print(ANSI_RESET);
            }
            out.println();
            reasoningOpen = false;
        } else if (contentStreamed) {
            out.println();
        }
        contentStreamed = false;
        out.print("\u001B[0m");
        out.flush();
        // 每轮成本/耗时小结（v0.26）：本回合结束时打印在回答下方，不跨回合串味。
        if (lastTurnUsage != null) {
            out.println(TokenUsageFormatter.formatPerTurn(
                    lastTurnUsage.provider(),
                    lastTurnUsage.model(),
                    lastTurnUsage.inputTokens(),
                    lastTurnUsage.outputTokens(),
                    lastTurnUsage.cachedInputTokens(),
                    lastTurnUsage.elapsedMillis()));
            out.flush();
            lastTurnUsage = null;
        }
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!reasoningOpen) {
            out.println();
            out.println(AnsiStyle.subtle("Thinking..."));
            if (AnsiStyle.isEnabled()) {
                out.print(REASONING_PREFIX);
            }
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
        closeReasoning();
        contentStreamed = true;
        markdownRenderer.append(delta);
    }

    @Override
    public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
        closeReasoning();
        if (contentStreamed) {
            markdownRenderer.finish();
            contentStreamed = false;
        }
        if (toolCalls == null) {
            return;
        }
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String name = toolCall.function().name();
            String args = compactArgs(toolCall.function().arguments());
            out.println(AnsiStyle.section("> tool " + name) + (args.isEmpty() ? "" : " " + AnsiStyle.subtle(args)));
        }
    }

    @Override
    public void onToolResult(ToolExecutionResult result) {
        if (result == null) {
            return;
        }
        if (result.timedOut()) {
            out.println(AnsiStyle.error("  (timeout) " + result.name()));
            return;
        }
        String text = result.result() == null ? "" : result.result();
        if (text.startsWith("🛡️") || text.startsWith("工具执行失败")) {
            out.println(AnsiStyle.error("  (!) " + result.name()));
            return;
        }
        out.println(AnsiStyle.subtle("  ok " + result.name() + " (" + result.elapsedMillis() + "ms)"));
    }

    @Override
    public void onTurnSummary(TurnUsage usage) {
        this.lastTurnUsage = usage;
    }

    private void closeReasoning() {
        if (reasoningOpen) {
            if (AnsiStyle.isEnabled()) {
                out.print(ANSI_RESET);
            }
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
