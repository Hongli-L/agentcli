package com.agentcli.agent.orchestration;

import com.agentcli.agent.concurrent.AgentTask;
import com.agentcli.agent.concurrent.AgentTaskExecutor;
import com.agentcli.agent.concurrent.AgentTaskState;
import com.agentcli.agent.concurrent.AgentTaskView;
import com.agentcli.audit.OrchestrationTrace;
import com.agentcli.agent.concurrent.CancellationScope;
import com.agentcli.agent.concurrent.CancellationToken;
import com.agentcli.llm.LlmClient;
import com.agentcli.plan.ExecutionPlan;
import com.agentcli.plan.Planner;
import com.agentcli.plan.Task;
import com.agentcli.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 多 Agent 编排门面：planner 规划 → worker 并行执行 → reviewer 复核 → 聚合降级。
 *
 * <p><b>门面的边界</b>：对外只有 {@link #run(String)} 一个入口。规划复用既有
 * {@link Planner} / {@link ExecutionPlan}（<b>不重写 DAG 与拓扑排序</b>），
 * 并发与取消下沉给 {@link AgentTaskExecutor}，执行单元池化在 {@link WorkerPool}，
 * 本类只负责「按拓扑分批、组装上下文、回写状态、聚合报告」这四件编排本职的事。
 *
 * <p><b>串行与并行是同一套代码路径的两种参数</b>（策略模式）：
 * {@code maxParallel <= 1} 走 {@link #runSerial}，否则走 {@link #runParallel}。
 * 两者共用 {@link #aggregate} 收口，保证降级语义、轨迹埋点与终端渲染完全一致。
 *
 * <p><b>部分失败降级</b>：单个子任务失败不会掀桌子。失败任务的下游因依赖未满足被标记
 * {@code SKIPPED}，其余分支照常跑完，最终产出一份带 ❌/⏭ 标记的报告，
 * {@code degraded=true}。这比「一个失败全盘丢弃」更贴近真实协作。
 *
 * <p><b>并行输出的有序性</b>：并行阶段禁止多线程直写同一 {@link PrintStream}，
 * 每个任务写自己的 {@link ByteArrayOutputStream}，批末按计划拓扑序 flush，
 * 终端看到的顺序稳定可复现。
 *
 * <p>职责对齐 {@code _reference/com/paicli/agent/AgentOrchestrator.java}，
 * 按主项目约定收敛：复用既有 Planner、拆出独立的取消底座与 worker 池。
 */
public final class AgentOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** borrow 等待预算：池尺寸 ≥ 并发上限时理论上永不等待，留一点余量兜住归还竞态。 */
    private static final long BORROW_TIMEOUT_MS = 30_000L;

    /** 单个任务结果在聚合报告里的回显长度。 */
    private static final int RESULT_PREVIEW_CHARS = 300;

    private final Planner planner;
    private final WorkerPool workerPool;
    private final ReviewerAgent reviewer;
    private final AgentTaskExecutor executor;
    private final boolean ownsExecutor;
    private final OrchestrationConfig config;

    /**
     * taskId → 对外 future（{@link AgentTaskExecutor#submit} 的返回值）。
     *
     * <p>只在编排线程（单线程）上读写：{@link #dispatch} 写、串行/并行路径读，
     * 因此用普通 {@link LinkedHashMap} 即可，保序也便于调试时按派发顺序查看。
     */
    private final Map<String, CompletableFuture<String>> submissions = new LinkedHashMap<>();

    private OrchestrationTrace trace;
    private PrintStream out = System.out;

    /**
     * 标准构造：自带一个私有的 {@link AgentTaskExecutor}，由本对象负责关停。
     *
     * <p><b>构造函数会串行预创建 {@code workerCount} 个 worker</b>（每个内部一个
     * {@code ReActAgent}），因此必须在单线程（编排线程 / REPL 线程）上构造。
     */
    public AgentOrchestrator(LlmClient llm, ToolRegistry tools, OrchestrationConfig cfg) {
        this(llm, tools, cfg, null);
    }

    /**
     * 共享执行器构造：由调用方（如 {@code InteractiveSession}）传入长生命周期的执行器。
     *
     * <p>这样 {@code /tasks} 与 {@code /cancel} 才能看见并操作本次编排派发的任务——
     * 若编排器每次都新建私有执行器并在结束时关掉，REPL 侧的任务视图会永远是空的。
     * 共享时本对象<b>不</b>负责关停它（谁创建谁关停）。
     *
     * @param sharedExecutor 外部执行器；为 {@code null} 时退化为标准构造
     */
    public AgentOrchestrator(LlmClient llm, ToolRegistry tools, OrchestrationConfig cfg,
                             AgentTaskExecutor sharedExecutor) {
        if (llm == null) {
            throw new IllegalArgumentException("llm 不能为 null");
        }
        this.config = cfg == null ? OrchestrationConfig.defaults() : cfg;
        this.planner = new Planner(llm, discardingStream());
        // 关键顺序：worker 与 reviewer 都在当前（单）线程上串行创建，构造期对
        // ToolRegistry 的注册写入因此天然串行化，见 WorkerPool 的类注释。
        this.workerPool = new WorkerPool(this.config.workerCount(), llm, tools);
        this.reviewer = this.config.reviewerEnabled() ? new ReviewerAgent(llm, tools) : null;
        this.ownsExecutor = sharedExecutor == null;
        this.executor = sharedExecutor == null
                ? new AgentTaskExecutor(this.config.maxParallel())
                : sharedExecutor;
    }

    /** 注入编排轨迹落盘器；{@code null} 表示不落盘（零成本 no-op）。 */
    public void setTrace(OrchestrationTrace trace) {
        this.trace = trace;
    }

    /** 设置面向用户的输出流（默认 {@code System.out}）。 */
    public void setOut(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    public OrchestrationConfig config() {
        return config;
    }

    /** 暴露 worker 池，供并发度与借还断言（测试用）。 */
    public WorkerPool workerPool() {
        return workerPool;
    }

    /** 暴露执行器，供 {@code /tasks} {@code /cancel} 与测试。 */
    public AgentTaskExecutor executor() {
        return executor;
    }

    /**
     * 唯一对外入口：把一个自然语言目标跑成一份多 Agent 协作报告。
     *
     * <p>本方法<b>不抛异常</b>——规划失败、循环依赖、目标为空都收敛为
     * {@link OrchestrationResult#failed(String)}，让 REPL 只需处理一种返回类型。
     */
    public OrchestrationResult run(String goal) {
        if (goal == null || goal.isBlank()) {
            return OrchestrationResult.failed("编排目标不能为空。");
        }
        CancellationToken token = new CancellationToken();
        ExecutionPlan plan;
        try {
            // 规划阶段也要可取消：这一步是一次完整 LLM 调用，可能耗时数十秒
            CancellationScope.bind(token);
            plan = planner.createPlan(goal.trim());
        } catch (IOException e) {
            log.warn("编排规划失败: {}", e.getMessage());
            return OrchestrationResult.failed("❌ 任务规划失败: " + describe(e));
        } catch (RuntimeException e) {
            log.warn("编排规划异常: {}", e.getMessage());
            return OrchestrationResult.failed("❌ 任务规划异常: " + describe(e));
        } finally {
            CancellationScope.unbind();
        }

        if (plan == null || plan.getAllTasks().isEmpty()) {
            return OrchestrationResult.failed("❌ 规划结果为空，无法编排。");
        }
        if (!plan.computeExecutionOrder()) {
            return OrchestrationResult.failed("❌ 计划中存在循环依赖，已中止编排。");
        }

        plan.markStarted();
        emitPlanned(plan);
        printHeader(plan);

        try {
            return config.serial()
                    ? runSerial(plan, token)
                    : runParallel(plan, token);
        } finally {
            if (plan.isAllCompleted()) {
                plan.markCompleted();
            } else {
                plan.markFailed();
            }
        }
    }

    // ------------------------------------------------------------------ 串行路径

    /**
     * 串行主从编排：按拓扑序逐个执行，每个任务仍走 {@link AgentTaskExecutor}
     * （从而共享同一套超时、取消与轨迹语义），只是立刻 {@code join}。
     */
    private OrchestrationResult runSerial(ExecutionPlan plan, CancellationToken token) {
        long deadline = System.currentTimeMillis() + config.globalTimeoutMs();
        List<AgentTask> dispatched = new ArrayList<>();
        List<Runnable> childHooks = new ArrayList<>();

        try {
            for (String taskId : plan.getExecutionOrder()) {
                Task planTask = plan.getTask(taskId);
                if (planTask == null || planTask.getStatus() != Task.TaskStatus.PENDING) {
                    continue;
                }
                if (stopRequested(token, deadline)) {
                    break;
                }
                if (!dependenciesSatisfied(plan, planTask)) {
                    // 上游失败 → 本任务不可执行，留给 aggregate 统一标 SKIPPED
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream taskOut = newBufferedStream(buffer);
                AgentTask agentTask = dispatch(plan, planTask, token, childHooks, taskOut);
                dispatched.add(agentTask);
                // 串行：立刻等它跑完。submit 返回的 future 永不异常完成，join 不会抛。
                CompletableFuture<String> future = submissionOf(agentTask);
                if (future != null) {
                    future.join();
                }
                syncPlanTaskState(planTask, agentTask);
                emitTerminal(plan, agentTask);
                taskOut.flush();
                writeBuffer(buffer);
            }
            return aggregate(plan, dispatched);
        } finally {
            releaseHooks(token, childHooks);
        }
    }

    // ------------------------------------------------------------------ 并行路径

    /**
     * 并行编排：拓扑分批，同批全部派发后用 {@code allOf().join()} 等齐。
     *
     * <p><b>为什么 join 不会因单任务失败而崩</b>：{@link AgentTaskExecutor#submit} 返回的是
     * 经过 {@code handle} 收敛的 future——失败/取消/超时都被转换成降级文本正常完成。
     * 这是「部分失败降级」能成立的技术前提。
     *
     * <p>并发上限由执行器池尺寸（{@code maxParallel}）硬性保证：即便一批里有 10 个任务，
     * 同时在跑的也不会超过池尺寸，其余在队列里等。
     */
    private OrchestrationResult runParallel(ExecutionPlan plan, CancellationToken token) {
        long deadline = System.currentTimeMillis() + config.globalTimeoutMs();
        List<AgentTask> dispatched = new ArrayList<>();
        List<Runnable> childHooks = new ArrayList<>();
        Map<String, Integer> order = topologicalIndex(plan);
        int guard = plan.getAllTasks().size() + 1;

        try {
            while (guard-- > 0) {
                if (stopRequested(token, deadline)) {
                    if (System.currentTimeMillis() >= deadline) {
                        out.println("⏱️ 已达全局编排预算，停止派发新任务。");
                        // 全局熔断：已在跑的任务一并取消，避免线程悬空
                        token.cancel();
                    }
                    break;
                }
                List<Task> batch = plan.getExecutableTasks();
                if (batch.isEmpty()) {
                    break;
                }

                Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
                Map<String, AgentTask> batchTasks = new LinkedHashMap<>();
                List<CompletableFuture<String>> futures = new ArrayList<>(batch.size());

                for (Task planTask : batch) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    PrintStream taskOut = newBufferedStream(buffer);
                    AgentTask agentTask = dispatch(plan, planTask, token, childHooks, taskOut);
                    buffers.put(planTask.getId(), buffer);
                    batchTasks.put(planTask.getId(), agentTask);
                    dispatched.add(agentTask);
                    CompletableFuture<String> future = submissionOf(agentTask);
                    if (future != null) {
                        futures.add(future);
                    }
                }

                // 批同步点：等齐本批才能算依赖满足，进而解锁下一批
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 回写 + 有序 flush：并行期间各写各的缓冲，这里才按拓扑序落到终端
                List<String> ids = new ArrayList<>(batchTasks.keySet());
                ids.sort(Comparator.comparingInt(id -> order.getOrDefault(id, Integer.MAX_VALUE)));
                for (String id : ids) {
                    AgentTask agentTask = batchTasks.get(id);
                    syncPlanTaskState(plan.getTask(id), agentTask);
                    emitTerminal(plan, agentTask);
                    writeBuffer(buffers.get(id));
                }
            }
            return aggregate(plan, dispatched);
        } finally {
            releaseHooks(token, childHooks);
        }
    }

    // ------------------------------------------------------------------ 派发与聚合

    /**
     * 组装一个子任务并提交给执行器。
     *
     * <p>任务体的 {@code finally} 三件套中，本方法负责其中之一：
     * <b>worker 必定归还池</b>（先 reset 再入队）。另外两件（{@code CancellationScope.unbind()}
     * 与钩子注销）分别由 {@link AgentTaskExecutor} 与 {@link #releaseHooks} 兜底。
     */
    private AgentTask dispatch(ExecutionPlan plan, Task planTask, CancellationToken parent,
                               List<Runnable> childHooks, PrintStream taskOut) {
        String prompt = StepContextBuilder.build(plan, planTask, config.depResultPreviewChars());
        CancellationToken child = childToken(parent, childHooks);
        AgentTask agentTask = new AgentTask(
                planTask.getId(), planTask.getDescription(), AgentRole.WORKER, child);

        planTask.markStarted();
        taskOut.println("▶ " + planTask.getId() + "  " + compact(planTask.getDescription(), 60));

        CompletableFuture<String> future = executor.submit(agentTask, () -> {
            WorkerAgent worker = workerPool.borrow(BORROW_TIMEOUT_MS);
            if (worker == null) {
                throw new IllegalStateException("worker 池借用超时，可能存在未归还的 worker");
            }
            try {
                return worker.execute(prompt);
            } finally {
                // finally 三件套之「worker 必定归还」：先 reset 再入队，见 WorkerPool#release
                workerPool.release(worker);
            }
        }, config.perTaskTimeoutMs());
        // 登记对外 future——串行路径要 join 它、并行路径要 allOf 它，漏登记会让等待直接落空
        submissions.put(agentTask.id(), future);

        emitDispatched(plan, agentTask);
        return agentTask;
    }

    /**
     * 聚合收口：残留任务标 SKIPPED → 渲染报告 → 一次性复核 → 计算降级标记。
     *
     * <p>复核放在这里而不是各执行路径里，是为了保证串行与并行拿到<b>完全一致</b>的
     * 复核输入与 token 成本（恒定一次调用）。
     */
    private OrchestrationResult aggregate(ExecutionPlan plan, List<AgentTask> tasks) {
        // 残留：从未被派发（上游失败导致依赖永不满足）或被熔断跳过
        for (Task planTask : plan.getAllTasks()) {
            if (planTask.getStatus() == Task.TaskStatus.PENDING
                    || planTask.getStatus() == Task.TaskStatus.RUNNING) {
                planTask.markSkipped();
            }
        }

        List<AgentTaskView> views = new ArrayList<>(tasks.size());
        for (AgentTask task : tasks) {
            views.add(task.toView());
        }

        String summaryText = renderSummary(plan);
        boolean degraded = plan.getAllTasks().stream()
                .anyMatch(t -> t.getStatus() != Task.TaskStatus.COMPLETED);

        ReviewVerdict verdict = null;
        if (reviewer != null && !views.isEmpty()) {
            verdict = reviewer.review(plan.getGoal(), summaryText);
            emitReviewed(plan, verdict);
        }
        return new OrchestrationResult(degraded, views, summaryText, verdict);
    }

    /** 把执行语义的终态回写为计划语义的状态，供依赖判定与报告渲染。 */
    private void syncPlanTaskState(Task planTask, AgentTask agentTask) {
        if (planTask == null || agentTask == null) {
            return;
        }
        AgentTaskState state = agentTask.state();
        switch (state) {
            case DONE -> planTask.markCompleted(agentTask.result());
            case FAILED -> planTask.markFailed(agentTask.error());
            case TIMEOUT -> planTask.markFailed("任务超时（超过 " + config.perTaskTimeoutMs() + "ms）");
            // plan.Task 没有 CANCELLED：取消不是错误，语义上更接近「没做」，故落 SKIPPED。
            // 完整保真的执行态仍保留在 AgentTaskView 里，报告与 /tasks 都从那里取。
            case CANCELLED -> planTask.markSkipped();
            default -> planTask.markFailed("任务未正常结束: " + state);
        }
    }

    // ------------------------------------------------------------------ 渲染

    private void printHeader(ExecutionPlan plan) {
        out.println();
        out.println("🤝 多 Agent 编排启动");
        out.println("   目标: " + compact(plan.getGoal(), 60));
        out.println("   子任务: " + plan.getAllTasks().size()
                + " 个 · 并发上限: " + config.maxParallel()
                + " · worker: " + workerPool.size()
                + " · 复核: " + (reviewer != null ? "开" : "关"));
        out.println();
    }

    private String renderSummary(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 编排总结\n");
        sb.append("   目标: ").append(compact(plan.getGoal(), 60)).append('\n');
        for (String taskId : plan.getExecutionOrder()) {
            Task task = plan.getTask(taskId);
            if (task == null) {
                continue;
            }
            sb.append("  ").append(statusIcon(task.getStatus())).append(' ')
                    .append(task.getId()).append("  ")
                    .append(compact(task.getDescription(), 50))
                    .append("  (").append(task.getDuration()).append("ms)\n");
            String detail = task.getStatus() == Task.TaskStatus.COMPLETED
                    ? task.getResult()
                    : task.getError();
            if (detail != null && !detail.isBlank()) {
                sb.append("     ").append(compact(detail, RESULT_PREVIEW_CHARS)).append('\n');
            } else if (task.getStatus() == Task.TaskStatus.SKIPPED) {
                sb.append("     （前置任务未成功，已跳过）\n");
            }
        }
        long done = plan.getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        sb.append("   完成 ").append(done).append('/').append(plan.getAllTasks().size());
        return sb.toString();
    }

    private static String statusIcon(Task.TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭";
            default -> "•";
        };
    }

    // ------------------------------------------------------------------ 轨迹埋点（trace 可为 null）

    private void emitPlanned(ExecutionPlan plan) {
        if (trace != null) {
            trace.planned(plan.getId(), plan.getAllTasks().size());
        }
    }

    private void emitDispatched(ExecutionPlan plan, AgentTask task) {
        if (trace != null) {
            trace.dispatched(plan.getId(), task);
        }
    }

    private void emitTerminal(ExecutionPlan plan, AgentTask task) {
        if (trace == null) {
            return;
        }
        switch (task.state()) {
            case DONE -> trace.completed(plan.getId(), task);
            case FAILED -> trace.failed(plan.getId(), task);
            case CANCELLED -> trace.cancelled(plan.getId(), task);
            case TIMEOUT -> trace.timedOut(plan.getId(), task);
            default -> trace.failed(plan.getId(), task);
        }
    }

    private void emitReviewed(ExecutionPlan plan, ReviewVerdict verdict) {
        if (trace != null) {
            trace.reviewed(plan.getId(), verdict);
        }
    }

    // ------------------------------------------------------------------ 工具方法

    /**
     * 为子任务派生独立令牌，并让父令牌能级联取消它。
     *
     * <p>一任务一令牌是 {@code /cancel task_3} 精确取消的前提；父子级联则让
     * {@code /cancel}（无参）与全局熔断能一次掀掉整轮编排。
     * 派生的钩子记入 {@code childHooks}，编排结束时统一注销，
     * 避免长会话里父令牌的钩子表无限增长（{@code CopyOnWriteArrayList} 写成本随长度线性上升）。
     */
    private CancellationToken childToken(CancellationToken parent, List<Runnable> childHooks) {
        CancellationToken child = new CancellationToken();
        if (parent != null) {
            childHooks.add(parent.onCancel(child::cancel));
        }
        return child;
    }

    private void releaseHooks(CancellationToken parent, List<Runnable> childHooks) {
        if (parent == null) {
            return;
        }
        for (Runnable hook : childHooks) {
            parent.removeHook(hook);
        }
        childHooks.clear();
    }

    /** 是否应停止派发：被取消，或已越过全局预算。 */
    private boolean stopRequested(CancellationToken token, long deadline) {
        return token.isCancelled() || System.currentTimeMillis() >= deadline;
    }

    private boolean dependenciesSatisfied(ExecutionPlan plan, Task task) {
        for (String depId : task.getDependencies()) {
            Task dep = plan.getTask(depId);
            if (dep == null || dep.getStatus() != Task.TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /** 拓扑序索引，用于并行批次的有序 flush。 */
    private Map<String, Integer> topologicalIndex(ExecutionPlan plan) {
        Map<String, Integer> index = new HashMap<>();
        List<String> order = plan.getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
        }
        return index;
    }

    /**
     * 取回某个任务对外的 future。
     *
     * <p>{@link AgentTaskExecutor#submit} 的返回值即为该 future；这里再包一层查表是为了
     * 让 {@link #dispatch} 保持「只负责派发」的单一职责。执行器内部注册表以 taskId 为键，
     * 同一轮编排内 taskId 唯一（复用 {@code plan.Task} 的 id）。
     */
    private CompletableFuture<String> submissionOf(AgentTask task) {
        return submissions.get(task.id());
    }

    /** 丢弃式输出流：{@link Planner} 会往 out 打印 "Planning task: ..."，编排自有更完整的表头。 */
    private static PrintStream discardingStream() {
        return new PrintStream(java.io.OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static PrintStream newBufferedStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    private void writeBuffer(ByteArrayOutputStream buffer) {
        if (buffer == null || buffer.size() == 0) {
            return;
        }
        out.print(buffer.toString(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String compact(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String single = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                .trim().replaceAll(" {2,}", " ");
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 关停：只在本对象拥有执行器时才关（共享执行器由创建方负责）。 */
    @Override
    public void close() {
        if (ownsExecutor) {
            executor.close();
        }
    }
}
