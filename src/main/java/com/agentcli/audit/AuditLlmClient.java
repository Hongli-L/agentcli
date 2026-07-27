package com.agentcli.audit;

import com.agentcli.llm.LlmClient;

import java.io.IOException;
import java.util.List;

/**
 * {@link LlmClient} 装饰器：透明转发所有调用，并在每次 {@code chat} 返回后
 * 把模型 / token / 耗时 / reasoning 摘要写入 {@link AuditLog}。
 *
 * <p>对上层 Agent 完全透明——它只看到一个普通的 {@link LlmClient}，
 * 因此 ReAct 引擎、Planner、对话压缩器等所有经 {@code LlmClient} 的调用都会被自动追踪。
 */
public class AuditLlmClient implements LlmClient {

    private static final int REASONING_MAX = 200;

    private final LlmClient delegate;
    private final AuditLog auditLog;

    public AuditLlmClient(LlmClient delegate, AuditLog auditLog) {
        this.delegate = delegate;
        this.auditLog = auditLog;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return traced(() -> delegate.chat(messages, tools));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return traced(() -> delegate.chat(messages, tools, listener));
    }

    private ChatResponse traced(IOExceptionSupplier<ChatResponse> call) throws IOException {
        long start = System.currentTimeMillis();
        ChatResponse response = call.get();
        long latency = System.currentTimeMillis() - start;
        String reasoning = response.reasoningContent();
        if (reasoning != null && reasoning.length() > REASONING_MAX) {
            reasoning = reasoning.substring(0, REASONING_MAX) + "…";
        }
        auditLog.recordLlm(delegate.getModelName(),
                response.inputTokens(), response.outputTokens(), response.cachedInputTokens(),
                latency, reasoning, response.hasToolCalls());
        return response;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @FunctionalInterface
    private interface IOExceptionSupplier<T> {
        T get() throws IOException;
    }
}
