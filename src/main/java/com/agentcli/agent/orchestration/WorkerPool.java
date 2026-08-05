package com.agentcli.agent.orchestration;

import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * worker 池：编排线程上<b>串行预创建</b> N 个 {@link WorkerAgent}，之后并行任务只借还、不新建。
 *
 * <p><b>这是本版最关键的一条并发红线。</b>{@code new ReActAgent(llm, tools)} 的构造函数会执行
 * {@code toolRegistry.register(new SaveMemoryTool(...))}，即对共享 {@code ToolRegistry} 内部的
 * {@code LinkedHashMap} 做 {@code put}。若在并行任务体内创建 worker，多个线程会并发修改同一张
 * 非线程安全的 map——轻则丢工具、重则破坏链表结构导致遍历死循环。
 * {@code ToolRegistry} 侧虽已包 {@code synchronizedMap} 做安全网，但<b>预创建仍是主要防线</b>：
 * 安全网只保证 map 不坏，不保证「构造期的副作用」按预期只发生一次。
 *
 * <p><b>为什么用 {@link BlockingQueue} 而不是信号量 + 数组</b>：借还语义就是「取一个 / 放回一个」，
 * {@code poll(timeout)} 天然提供了带超时的等待，且队列本身线程安全，不需要额外的锁与条件变量。
 * 池尺寸固定，用有界 {@link ArrayBlockingQueue} 顺便把「归还超量」变成可观测的失败。
 *
 * <p>职责参考 {@code _reference/com/paicli/agent/AgentOrchestrator.java} 的多 agent 管理，
 * 按主项目约定收敛为独立的池对象，便于测试与并发度断言。
 */
public final class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final BlockingQueue<WorkerAgent> idle;
    private final List<WorkerAgent> all;

    /**
     * 串行预创建 {@code size} 个 worker。
     *
     * <p>本构造函数<b>必须</b>在编排线程（单线程）上调用。
     *
     * @param size  池尺寸，小于 1 时按 1 处理
     * @param llm   共享 LLM 客户端
     * @param tools 共享工具注册表
     */
    public WorkerPool(int size, LlmClient llm, ToolRegistry tools) {
        int normalized = Math.max(1, size);
        this.idle = new ArrayBlockingQueue<>(normalized);
        List<WorkerAgent> created = new ArrayList<>(normalized);
        for (int i = 1; i <= normalized; i++) {
            WorkerAgent worker = new WorkerAgent("worker-" + i, llm, tools);
            created.add(worker);
            idle.add(worker);
        }
        this.all = List.copyOf(created);
        log.debug("WorkerPool 预创建完成: size={}", normalized);
    }

    /**
     * 借一个空闲 worker。
     *
     * @param timeoutMs 最长等待毫秒数，{@code <= 0} 表示不等待（立即返回或 {@code null}）
     * @return 空闲 worker；等待超时返回 {@code null}
     * @throws InterruptedException 等待期间被中断（L2 取消会走到这里，必须向上传播）
     */
    public WorkerAgent borrow(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            return idle.poll();
        }
        return idle.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 归还 worker：<b>先 reset 再入队</b>。
     *
     * <p>顺序不能反——先入队会让另一个线程立刻借走一个还带着上轮历史的 agent。
     * reset 失败不阻断归还：一个脏 worker 也好过池里永久少一个 worker（会导致后续 borrow 超时）。
     */
    public void release(WorkerAgent worker) {
        if (worker == null) {
            return;
        }
        try {
            worker.reset();
        } catch (RuntimeException e) {
            log.warn("worker {} 重置失败，仍继续归还: {}", worker.name(), e.getMessage());
        }
        if (!idle.offer(worker)) {
            // 有界队列拒绝 = 归还了池外对象或重复归还，属于编程错误，记日志便于定位
            log.warn("worker {} 归还失败：池已满（疑似重复归还）", worker.name());
        }
    }

    /** 池尺寸（预创建的 worker 总数）。 */
    public int size() {
        return all.size();
    }

    /** 当前空闲 worker 数，供测试断言「借出后减少、归还后复原」。 */
    public int available() {
        return idle.size();
    }
}
