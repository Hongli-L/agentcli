package com.agentcli.testsupport;

import com.agentcli.llm.LlmClient;
import com.agentcli.llm.PromptCacheMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 测试用 LLM 客户端：按队列返回预设响应。 */
public final class StubLlmClient implements LlmClient {

    private final String modelName;
    private final String providerName;
    private final List<Object> scripted = new ArrayList<>();
    private int chatCalls;
    private boolean promptCachingSupported;
    private String promptCacheMode = PromptCacheMode.NONE;

    public StubLlmClient(String modelName, String providerName) {
        this.modelName = modelName;
        this.providerName = providerName;
    }

    /**
     * 配置提示词缓存元数据。不调用时与 {@link LlmClient} 的接口默认值一致
     * （不支持 / {@link PromptCacheMode#NONE}），因此既有用例行为不变。
     */
    public void configurePromptCache(boolean supported, String mode) {
        this.promptCachingSupported = supported;
        this.promptCacheMode = mode == null || mode.isBlank() ? PromptCacheMode.NONE : mode;
    }

    public void enqueue(LlmClient.ChatResponse response) {
        scripted.add(response);
    }

    public void enqueueFailure(IOException error) {
        scripted.add(error);
    }

    public int chatCalls() {
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

    @Override
    public boolean supportsPromptCaching() {
        return promptCachingSupported;
    }

    @Override
    public String promptCacheMode() {
        return promptCacheMode;
    }
}
