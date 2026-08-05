package com.agentcli.testsupport;

import com.agentcli.llm.LlmClient;
import com.agentcli.llm.PromptCacheMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发安全的桩 LLM 客户端：{@link StubLlmClient} 的多线程版本。
 *
 * <p><b>为什么不能直接复用 {@link StubLlmClient}</b>：它用「先进先出的脚本队列」决定返回值，
 * 这在并行编排下彻底失效——3 个 worker 谁先拿到队首完全取决于线程调度，
 * 断言会随机红绿。本类改用<b>按内容路由</b>：从请求里识别调用方身份（planner / reviewer / worker），
 * worker 再按 {@code task_N} 精确匹配，与线程调度顺序无关，因此断言是确定性的。
 *
 * <p><b>为什么自带并发度观测</b>：验证「并发上限 = 3」这类断言必须在<b>任务运行中</b>取样，
 * 事后统计总调用数是看不出并发度的。本类在每次 worker 调用进出时维护
 * {@link #activeWorkers()} 与 {@link #peakConcurrency()}，让测试能直接断言峰值。
 *
 * <p><b>为什么用闸门而不是 sleep</b>：{@code Thread.sleep} 做时序假设在 CI 上必然偶发翻车。
 * 本类提供 {@link #gate()} —— worker 调用会阻塞在闸门上直到测试显式放行，
 * 配合 {@link #awaitWorkerArrivals(int, long)} 可以精确捕捉「恰好 N 个 worker 同时在跑」的瞬间。
 *
 * <p>线程安全：所有可变状态为并发容器或原子量；{@link #prompts()} 返回防御性拷贝。
 */
public final class ConcurrentStubLlmClient implements LlmClient {

    /** 识别 planner 调用的特征串（取自 {@code Planner.PLANNER_SYSTEM_PROMPT}）。 */
    public static final String PLANNER_MARKER = "你是一个任务规划器";

    /** 识别 reviewer 调用的特征串（取自 {@code ReviewerAgent.REVIEW_PROMPT_TEMPLATE}）。 */
    public static final String REVIEWER_MARKER = "你是一个严格的质量复核员";

    /** 识别 worker 调用的特征串（取自 {@code StepContextBuilder}）。 */
    public static final String WORKER_MARKER = "【当前子任务】";

    /** 默认复核结论：通过。 */
    public static final String DEFAULT_REVIEW_JSON =
            "{\"approved\": true, \"issues\": \"\", \"summary\": \"结果达成目标\"}";

    private final String modelName;
    private final String providerName;

    private volatile String planJson;
    private volatile String reviewJson = DEFAULT_REVIEW_JSON;
    private volatile String defaultWorkerAnswer = "已完成该子任务。";
    private volatile IOException planFailure;
    private volatile RuntimeException reviewFailure;

    private final Map<String, String> workerAnswers = new ConcurrentHashMap<>();
    private final Set<String> runtimeFailingTasks = ConcurrentHashMap.newKeySet();
    private final Set<String> ioFailingTasks = ConcurrentHashMap.newKeySet();
    private final Set<String> gatedTasks = ConcurrentHashMap.newKeySet();

    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger plannerCalls = new AtomicInteger();
    private final AtomicInteger reviewerCalls = new AtomicInteger();
    private final AtomicInteger workerCalls = new AtomicInteger();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger peakConcurrency = new AtomicInteger();

    /** worker 到达闸门时递减；测试用它等待「已有 N 个 worker 进入」。 */
    private volatile CountDownLatch arrivals = new CountDownLatch(0);

    /** 闸门本体：worker 阻塞在这里直到 {@link #openGate()}。初始为已放行。 */
    private volatile CountDownLatch gate = new CountDownLatch(0);

    /** 闸门等待上限，兜住测试忘记放行导致的永久挂起。 */
    private volatile long gateTimeoutMs = 10_000L;

    private volatile boolean promptCachingSupported;
    private volatile String promptCacheMode = PromptCacheMode.NONE;

    public ConcurrentStubLlmClient(String modelName, String providerName) {
        this.modelName = modelName;
        this.providerName = providerName;
    }

    // ------------------------------------------------------------------ 脚本配置

    /** 设置 planner 返回的计划 JSON。未设置时 planner 调用会抛 {@link IOException}。 */
    public ConcurrentStubLlmClient withPlan(String json) {
        this.planJson = json;
        return this;
    }

    /** 让 planner 调用失败（验证「规划失败 → OrchestrationResult.failed」）。 */
    public ConcurrentStubLlmClient withPlanFailure(IOException error) {
        this.planFailure = error;
        return this;
    }

    /** 设置 reviewer 返回的原始文本（可以是非 JSON，用于验证兜底解析）。 */
    public ConcurrentStubLlmClient withReview(String raw) {
        this.reviewJson = raw;
        return this;
    }

    /** 让 reviewer 抛未检查异常（验证「复核挂掉不影响编排」）。 */
    public ConcurrentStubLlmClient withReviewFailure(RuntimeException error) {
        this.reviewFailure = error;
        return this;
    }

    /** 为指定子任务设置固定回答。 */
    public ConcurrentStubLlmClient withWorkerAnswer(String taskId, String answer) {
        workerAnswers.put(taskId, answer);
        return this;
    }

    /** 未单独配置的子任务的默认回答。 */
    public ConcurrentStubLlmClient withDefaultWorkerAnswer(String answer) {
        this.defaultWorkerAnswer = answer;
        return this;
    }

    /**
     * 让指定子任务抛<b>未检查</b>异常。
     *
     * <p>这会穿透 {@code ReActAgent}（它只捕获 {@link IOException}）一路冒泡到
     * {@code AgentTaskExecutor}，最终把任务落成 {@code FAILED} —— 这是构造「真失败」的唯一途径。
     */
    public ConcurrentStubLlmClient withRuntimeFailure(String taskId) {
        runtimeFailingTasks.add(taskId);
        return this;
    }

    /**
     * 让指定子任务抛 {@link IOException}。
     *
     * <p>与 {@link #withRuntimeFailure} 的区别：{@code ReActAgent} 会捕获它并返回一段
     * 「LLM 调用失败」文本，任务状态仍是 {@code DONE}。用于验证「网络抖动被优雅降级」。
     */
    public ConcurrentStubLlmClient withIoFailure(String taskId) {
        ioFailingTasks.add(taskId);
        return this;
    }

    /** 只让指定任务受闸门约束；不调用则闸门对所有 worker 生效。 */
    public ConcurrentStubLlmClient gateOnly(String... taskIds) {
        Collections.addAll(gatedTasks, taskIds);
        return this;
    }

    public ConcurrentStubLlmClient withGateTimeoutMs(long timeoutMs) {
        this.gateTimeoutMs = timeoutMs;
        return this;
    }

    public void configurePromptCache(boolean supported, String mode) {
        this.promptCachingSupported = supported;
        this.promptCacheMode = mode == null || mode.isBlank() ? PromptCacheMode.NONE : mode;
    }

    // ------------------------------------------------------------------ 闸门控制

    /**
     * 关闸并准备等待 {@code expectedArrivals} 个 worker 到达。
     *
     * @param expectedArrivals 预期同时到达闸门的 worker 数
     */
    public void closeGate(int expectedArrivals) {
        this.arrivals = new CountDownLatch(expectedArrivals);
        this.gate = new CountDownLatch(1);
    }

    /** 放行全部阻塞中的 worker。 */
    public void openGate() {
        CountDownLatch current = gate;
        if (current != null) {
            current.countDown();
        }
    }

    /** 当前闸门（供测试直接 await）。 */
    public CountDownLatch gate() {
        return gate;
    }

    /**
     * 阻塞直到已有 {@code count} 个 worker 到达闸门。
     *
     * @return true 表示在超时前达成
     */
    public boolean awaitWorkerArrivals(int count, long timeoutMs) throws InterruptedException {
        CountDownLatch latch = arrivals;
        if (latch.getCount() > count) {
            // 调用方要等的数量少于 closeGate 声明的数量：退化为轮询计数，避免永久等待
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (activeWorkers.get() >= count) {
                    return true;
                }
                Thread.onSpinWait();
            }
            return false;
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 观测

    public int chatCalls() {
        return chatCalls.get();
    }

    public int plannerCalls() {
        return plannerCalls.get();
    }

    public int reviewerCalls() {
        return reviewerCalls.get();
    }

    public int workerCalls() {
        return workerCalls.get();
    }

    public int activeWorkers() {
        return activeWorkers.get();
    }

    /** 观测到的 worker 并发峰值。 */
    public int peakConcurrency() {
        return peakConcurrency.get();
    }

    /** 全部请求的用户消息文本（防御性拷贝）。 */
    public List<String> prompts() {
        synchronized (prompts) {
            return List.copyOf(prompts);
        }
    }

    /** 找到某个子任务的 worker prompt，用于断言上下文隔离。 */
    public String promptForTask(String taskId) {
        for (String prompt : prompts()) {
            if (prompt.contains(WORKER_MARKER + taskId + "\n")) {
                return prompt;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ LlmClient

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
            throws IOException {
        chatCalls.incrementAndGet();
        String all = flatten(messages);
        prompts.add(all);

        if (all.contains(PLANNER_MARKER)) {
            return respondAsPlanner(listener);
        }
        if (all.contains(REVIEWER_MARKER)) {
            return respondAsReviewer(listener);
        }
        return respondAsWorker(all, listener);
    }

    private ChatResponse respondAsPlanner(StreamListener listener) throws IOException {
        plannerCalls.incrementAndGet();
        if (planFailure != null) {
            throw planFailure;
        }
        if (planJson == null) {
            throw new IOException("未配置 planJson");
        }
        return emit(planJson, listener);
    }

    private ChatResponse respondAsReviewer(StreamListener listener) {
        reviewerCalls.incrementAndGet();
        if (reviewFailure != null) {
            throw reviewFailure;
        }
        return emit(reviewJson, listener);
    }

    private ChatResponse respondAsWorker(String prompt, StreamListener listener) throws IOException {
        workerCalls.incrementAndGet();
        String taskId = extractTaskId(prompt);
        int active = activeWorkers.incrementAndGet();
        peakConcurrency.accumulateAndGet(active, Math::max);
        try {
            passGate(taskId);
            if (runtimeFailingTasks.contains(taskId)) {
                throw new IllegalStateException("worker 故障注入: " + taskId);
            }
            if (ioFailingTasks.contains(taskId)) {
                throw new IOException("网络故障注入: " + taskId);
            }
            String answer = workerAnswers.getOrDefault(taskId, defaultWorkerAnswer);
            return emit(answer, listener);
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    /** 到达闸门：先登记到达，再等待放行（未关闸时两步都是立即返回）。 */
    private void passGate(String taskId) {
        if (!gatedTasks.isEmpty() && !gatedTasks.contains(taskId)) {
            return;
        }
        CountDownLatch arrival = arrivals;
        if (arrival != null) {
            arrival.countDown();
        }
        CountDownLatch current = gate;
        if (current == null || current.getCount() == 0) {
            return;
        }
        try {
            if (!current.await(gateTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("闸门等待超时，测试可能忘记 openGate(): " + taskId);
            }
        } catch (InterruptedException e) {
            // 取消路径会中断 worker 线程：恢复中断位并往上抛，让执行器落成 CANCELLED
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker 被中断: " + taskId, e);
        }
    }

    /** 从 worker prompt 中抠出 {@code 【当前子任务】task_N} 的任务 id。 */
    private static String extractTaskId(String prompt) {
        int start = prompt.indexOf(WORKER_MARKER);
        if (start < 0) {
            return "";
        }
        int from = start + WORKER_MARKER.length();
        int end = prompt.indexOf('\n', from);
        return end < 0 ? prompt.substring(from).trim() : prompt.substring(from, end).trim();
    }

    private ChatResponse emit(String content, StreamListener listener) {
        if (listener != null && content != null) {
            listener.onContentDelta(content);
        }
        return new ChatResponse("assistant", content, null, List.of(), 10, 5);
    }

    private static String flatten(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        for (Message message : messages) {
            if (message != null && message.content() != null) {
                sb.append(message.content()).append('\n');
            }
        }
        return sb.toString();
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
