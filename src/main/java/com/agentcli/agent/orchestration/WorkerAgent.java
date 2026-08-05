package com.agentcli.agent.orchestration;

import com.agentcli.agent.ReActAgent;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;

/**
 * 执行单元：把一个独立的 {@link ReActAgent} 包成「可借还的 worker」。
 *
 * <p><b>为什么每个 worker 一个独立 {@code ReActAgent} 而不是共享一个</b>：
 * {@code ReActAgent} 内部持有可变的 {@code conversationHistory}（裸 {@code ArrayList}）
 * 与每轮新建的 {@code AgentBudget}。多个子任务共享同一实例会互相污染历史、
 * 抢同一份 token 预算，并在并行时对同一个 {@code ArrayList} 并发写。
 * 一 worker 一 agent 后，历史与预算天然按任务隔离，无需任何加锁。
 *
 * <p><b>创建时机的硬约定</b>：构造函数会间接触发
 * {@code toolRegistry.register(new SaveMemoryTool(...))}，对共享 {@link ToolRegistry}
 * 的 map 做写操作。因此 worker <b>只能在编排线程上串行预创建</b>（见 {@link WorkerPool}），
 * 绝不允许在并行任务体内 {@code new WorkerAgent(...)}。
 *
 * <p>职责对齐 {@code _reference/com/paicli/agent/AgentOrchestrator.java} 里的从属 agent 概念，
 * 但把「创建」与「使用」拆开，以适配池化复用。
 */
public final class WorkerAgent {

    private final String name;
    private final ReActAgent delegate;

    /**
     * @param name  worker 名称，用于日志与轨迹（如 {@code worker-1}）
     * @param llm   LLM 客户端（可与其它 worker 共享，客户端本身无可变会话状态）
     * @param tools 共享工具注册表（已做同步加固，且此处仍在编排线程上串行注册）
     */
    public WorkerAgent(String name, LlmClient llm, ToolRegistry tools) {
        if (llm == null) {
            throw new IllegalArgumentException("llm 不能为 null");
        }
        this.name = name == null || name.isBlank() ? "worker" : name.trim();
        this.delegate = tools == null ? new ReActAgent(llm) : new ReActAgent(llm, tools);
    }

    public String name() {
        return name;
    }

    /**
     * 执行一个子任务。
     *
     * <p>{@code taskPrompt} 由 {@link StepContextBuilder} 生成——只含总目标、当前子任务
     * 与依赖任务的<b>结果摘要</b>，<b>不含</b>上游 worker 的对话历史，这是上下文隔离的落点。
     */
    public String execute(String taskPrompt) {
        return delegate.run(taskPrompt);
    }

    /**
     * 归还池前清空对话历史。
     *
     * <p>不清会让下一个借到它的任务读到上一个任务的全部消息——既污染语义，
     * 又白白多烧 token（历史会随 prompt 一起发给模型）。
     */
    public void reset() {
        delegate.clearHistory();
    }

    /** 暴露委托 agent，供编排层设置监听器与断言（测试用）。 */
    public ReActAgent delegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "WorkerAgent[" + name + "]";
    }
}
