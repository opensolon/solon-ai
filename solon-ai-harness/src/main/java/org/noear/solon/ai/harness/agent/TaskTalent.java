/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.LlmErrorClassifier;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理才能：把子代理暴露为 {@code task} / {@code multitask} 工具。
 *
 * <p><b>并发契约</b>：按模型分组限流，生效上限 = 模型级 {@code AiConfig#concurrency}
 * （0 = 未设置）优先，否则取 {@link #DEFAULT_CONCURRENCY}，上界 {@link #MAX_CONCURRENCY}。
 * 恒为有限正整数，<b>不存在「不限制」</b>——那会让线程数、上游 QPS 与堆内存退化为只受任务数约束。
 * 需要更高并发请显式配置模型级 {@code concurrency}。</p>
 *
 * <p><b>不设超时</b>：子任务可能运行数天，所有等待路径只有轮询节拍、没有截止时间，
 * 只会因主流程取消或线程中断提前结束。代价是长任务会长期占着槽位，排队超过
 * {@link #SLOT_WARN_MS} 会打一条 WARN 供排查。</p>
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(TaskTalent.class);

    public static final String TOOL_TASK = "task";
    public static final String TOOL_MULTITASK = "multitask";

    /** 默认并发上限的缺省值：配置为 0（未设置）时使用 */
    public static final int DEFAULT_CONCURRENCY = 5;

    /** 默认并发上限的硬上界：默认值是兜底安全网，不是放开并发的入口 */
    public static final int MAX_CONCURRENCY = 10;

    /** 无槽位标记：任务本身不可解析（子代理未知 / 模型不可用），立即失败，不构成并发压力 */
    private static final int CAP_NONE = 0;

    /** 委派深度标记：子代理任务线程内可见，用于拒绝嵌套委派 */
    private static final ThreadLocal<Boolean> TASK_DEPTH = new ThreadLocal<>();

    /** 等待槽位的轮询节拍：取消能及时响应，又不至于空转 */
    private static final long SLOT_POLL_MS = 200L;

    /** 排队过久的告警阈值（每次等待只打一次 WARN） */
    private static final long SLOT_WARN_MS = 30000L;

    /** 单条结果的字符上限：避免单个子代理的输出独占父会话上下文 */
    private static final int MAX_RESULT_CHARS = 20000;

    private final HarnessEngine engine;

    /**
     * 模型级槽位。键取 {@link ChatConfig#getNameOrModel()}：{@code HarnessOptions#addModel}
     * 同样以它为键，因此同一引擎内不会出现「同名但配置不同」的两份模型。
     */
    private final Map<Object, ModelSlot> modelSlots = new ConcurrentHashMap<>();

    /**
     * 子代理默认并发上限（模型未配置时生效）。
     *
     * <p>恒为 {@code [1, MAX_CONCURRENCY]}：0 已被「未设置」占用，不能再兼任「不限制」，
     * 写入侧一律经 {@link #normalizeDefaultConcurrency(int)} 归一。</p>
     */
    private volatile int defaultConcurrency = DEFAULT_CONCURRENCY;

    /**
     * 子任务执行器（引擎私有）。
     *
     * <p>不复用 {@code RunUtil.io()}：分派线程若跑在该全局池上，等待槽位会把池线程占满，
     * 拿到槽位的任务永远等不到线程执行，许可不释放，形成死锁。</p>
     *
     * <p>必须用 {@link SynchronousQueue}：core=0 配无界队列时，ThreadPoolExecutor 只在
     * {@code workerCount < corePoolSize} 时建线程，实际并发恒为 1。这里是获许可才提交，
     * 线程数不超过各模型生效上限之和。</p>
     */
    private final ExecutorService taskExecutor;

    public TaskTalent(HarnessEngine engine) {
        this.engine = engine;
        this.taskExecutor = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new TaskThreadFactory());
    }

    /**
     * @param defaultConcurrency 子代理默认并发上限；归一规则见 {@link #normalizeDefaultConcurrency(int)}
     */
    public void setDefaultConcurrency(int defaultConcurrency) {
        this.defaultConcurrency = normalizeDefaultConcurrency(defaultConcurrency);
    }

    /** 子代理默认并发上限（模型未配置时生效），恒为正整数 */
    public int getDefaultConcurrency() {
        return defaultConcurrency;
    }

    /**
     * 归一化默认并发上限（配置侧与执行侧共用同一份规则）。
     *
     * <p>0 = 未设置 → {@link #DEFAULT_CONCURRENCY}；超上界 → {@link #MAX_CONCURRENCY}；
     * 负数 → {@link IllegalArgumentException}。归一与截断都打 WARN，避免「配了却没生效」变成静默降级。</p>
     */
    public static int normalizeDefaultConcurrency(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("subagentDefaultConcurrency must be >= 0: " + value);
        }

        if (value == 0) {
            LOG.warn("subagentDefaultConcurrency=0 不再表示「不限制」，已按「未设置」处理并取默认 {}；"
                    + "需要更高并发请显式配置模型级 AiConfig#concurrency", DEFAULT_CONCURRENCY);
            return DEFAULT_CONCURRENCY;
        }

        if (value > MAX_CONCURRENCY) {
            LOG.warn("subagentDefaultConcurrency={} 超出默认值上界 {}，已按 {} 处理；"
                    + "需要更高并发请显式配置模型级 AiConfig#concurrency", value, MAX_CONCURRENCY, MAX_CONCURRENCY);
            return MAX_CONCURRENCY;
        }

        return value;
    }

    /**
     * 释放执行器线程。不可恢复；线程为守护线程，常规场景无需调用。
     */
    public void shutdown() {
        taskExecutor.shutdown();
    }

    private static class TaskThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "solon-ai-multitask-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    @Override
    public String description() {
        return "多任务调度专家：将复杂任务拆解并委派给专项子代理（如 explore, plan, bash 等），支持并行处理以提高效率。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 当前可用的子代理\n");
        sb.append("<available_agents>\n");
        for (AgentDefinition agentDefinition : engine.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agentDefinition.getName(), agentDefinition.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("## 任务分配策略：\n");
        sb.append("1. **优先并行**: 当任务可以被拆分为互不干扰的独立单元（例如：不同的模块开发、多路搜索）时，可使用 `multitask` 节省时间。\n");
        sb.append("2. **原子性**: 每个子任务应具备明确的边界。\n");
        sb.append("3. **上下文感知**: 必须在 prompt 中提供任务所需的全部背景，子代理无法看到主会话的完整历史。\n");

        return sb.toString();
    }

    @ToolMapping(name = TOOL_TASK, description =
            "委派单一任务给专项子代理。适用于需要深度思考、多步操作或特定领域知识（如文件操作、代码分析）的场景。不支持并行调用（并行请用 multitask）。")
    public String task(@Body SingleTaskOp taskSpec, String __cwd, String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return "ERROR: 子代理不允许再委派子任务（最多一层）。请直接完成当前任务，或把需要的内容整理进 prompt 后自行执行。";
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());
        final FluxSink<AgentEvent> parentSink = streamSinkOf(__parentTrace);

        MultiTaskOp taskOp = new MultiTaskOp();
        taskOp.agent_name = taskSpec.agent_name;
        taskOp.description = taskSpec.description;
        taskOp.prompt = taskSpec.prompt;

        MultitaskContext ctx = new MultitaskContext(__parentTrace, __cwd, __sessionId, __parentSession, 1,
                null, null, null);

        TaskPlan plan = preparePlan(taskOp, sessionModelOf(__parentSession));

        // 单任务同样取槽：信号量是引擎级、跨会话共享的，绕开它会让 concurrency=1 的模型
        // 在多会话各跑一个 task 时完全不受限流
        if (plan.slot != null && !acquirePolling(plan.slot, parentSink)) {
            return formatTaskResp(taskOp, false, notStartedReason(), false);
        }

        try {
            return runTask(ctx, plan, false, Utils.uuid());
        } finally {
            releaseQuietly(plan.slot);
        }
    }

    @ToolMapping(name = TOOL_MULTITASK, description =
            "并行执行多个互不依赖的子任务。要求任务之间必须没有资源竞争（例如：不同的模块开发、多路搜索）。")
    public String multitask(@Param(name = "tasks", description = "任务列表") List<MultiTaskOp> tasks, String __cwd, String __sessionId) {
        if (Assert.isEmpty(tasks)) {
            return "WARNING: 任务列表为空";
        }

        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return "ERROR: 子代理不允许再委派子任务（最多一层）。请直接完成当前任务；如需并行，请返回后由主代理统一编排。";
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());
        final FluxSink<AgentEvent> parentSink = streamSinkOf(__parentTrace);

        final MultitaskContext ctx = new MultitaskContext(__parentTrace, __cwd, __sessionId, __parentSession,
                tasks.size(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());

        // 预解析一次并向下传递：分派与执行各解析一遍时，「默认模型」的 fallback 一旦有歧义，
        // 限流用的模型就会与真正跑的模型分叉
        final List<TaskPlan> plans = preparePlans(tasks, sessionModelOf(__parentSession));

        // 定长 + 按下标 set：边算边 add 会打乱顺序，导致 <index> 与 <task_result> 错位
        final List<CompletableFuture<String>> futures = new ArrayList<>(Collections.nCopies(plans.size(), null));

        if (isSerialOnly(plans)) {
            dispatchSerial(plans, futures, ctx, parentSink);
        } else {
            dispatchParallel(plans, futures, ctx, parentSink);
        }

        return aggregate(plans, futures, ctx);
    }

    // ========== 预解析 ==========

    /** 一个子任务的执行计划：预解析产物，供分派与执行共用 */
    static final class TaskPlan {
        final MultiTaskOp op;
        final AgentDefinition agentDefinition;
        /** 已解析出的具体模型名（可为 null） */
        final String modelName;
        final Semaphore slot;
        /** 生效并发上限；{@link #CAP_NONE} 表示无槽位 */
        final int cap;
        /** 非空表示任务定义本身不可用，直接回该文案 */
        final String invalidReason;

        TaskPlan(MultiTaskOp op, AgentDefinition agentDefinition, String modelName,
                 Semaphore slot, int cap, String invalidReason) {
            this.op = op;
            this.agentDefinition = agentDefinition;
            this.modelName = modelName;
            this.slot = slot;
            this.cap = cap;
            this.invalidReason = invalidReason;
        }
    }

    private List<TaskPlan> preparePlans(List<MultiTaskOp> tasks, String sessionModel) {
        final List<TaskPlan> plans = new ArrayList<>(tasks.size());

        for (int i = 0; i < tasks.size(); i++) {
            MultiTaskOp op = tasks.get(i);

            if (op == null) {
                // JSON 反序列化可能带 null 元素：不能 NPE，也不能少一条导致下标错位
                MultiTaskOp empty = new MultiTaskOp();
                empty.index = i + 1;
                empty.agent_name = "";
                empty.description = "";
                empty.prompt = "";
                plans.add(new TaskPlan(empty, null, null, null, CAP_NONE,
                        "ERROR: 第 " + (i + 1) + " 个任务定义为空（null），已跳过。"));
                continue;
            }

            plans.add(preparePlan(op, sessionModel));
        }

        return plans;
    }

    private TaskPlan preparePlan(MultiTaskOp op, String sessionModel) {
        final AgentDefinition agentDefinition;

        try {
            // getAgent 对未知名称是抛异常而非返回 null，必须先 hasAgent：
            // 否则模型幻觉一个 agent 名就会让整批任务全废
            agentDefinition = engine.getAgentManager().hasAgent(op.agent_name)
                    ? engine.getAgentManager().getAgent(op.agent_name) : null;
        } catch (Throwable e) {
            // 名称存在但定义加载失败：同样只废这一条
            return new TaskPlan(op, null, null, null, CAP_NONE,
                    "ERROR: 子代理 '" + op.agent_name + "' 定义加载失败: " + e.getMessage());
        }

        if (agentDefinition == null) {
            // 走 invalidReason 而非裸文本：否则 multitask 里会出现没有外壳、无法对位的一行
            return new TaskPlan(op, null, null, null, CAP_NONE, unknownAgentReason(op));
        }

        ChatConfig taskModelConfig = resolveTaskModelConfig(agentDefinition, sessionModel);
        // 生效上限只解析一次：defaultConcurrency 是 volatile，读两次可能得到
        // 「信号量按新值、cap 记旧值」的错配
        final int limit = finalConcurrency(taskModelConfig);

        return new TaskPlan(op, agentDefinition,
                taskModelConfig == null ? null : taskModelConfig.getNameOrModel(),
                semaphoreOf(taskModelConfig, limit), limit, null);
    }

    private static String unknownAgentReason(MultiTaskOp op) {
        return "ERROR: 未知的子代理类型 '" + op.agent_name + "'。";
    }

    /** 串行判定：必须是「全部」生效上限为 1，任一为 1 就把整批拖成串行是错误的 */
    static boolean isSerialOnly(List<TaskPlan> plans) {
        for (TaskPlan plan : plans) {
            if (plan.cap != 1) {
                return false;
            }
        }

        // 空批次不能断定为串行
        return !plans.isEmpty();
    }

    private static String sessionModelOf(AgentSession parentSession) {
        return parentSession.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
    }

    private static FluxSink<AgentEvent> streamSinkOf(ReActTrace parentTrace) {
        return (parentTrace != null && parentTrace.getOptions() != null)
                ? parentTrace.getOptions().getStreamSink() : null;
    }

    // ========== 分派 ==========

    /**
     * 串行快速路径：所有任务生效上限都为 1 时在调用线程逐个执行，省去线程投递。
     */
    private void dispatchSerial(List<TaskPlan> plans, List<CompletableFuture<String>> futures,
                                MultitaskContext ctx, FluxSink<AgentEvent> sink) {
        for (int i = 0; i < plans.size(); i++) {
            TaskPlan plan = plans.get(i);

            if (isCancelled(sink)) {
                futures.set(i, notStarted(plan.op));
                continue;
            }

            if (plan.slot != null && !acquirePolling(plan.slot, sink)) {
                futures.set(i, notStarted(plan.op));
                continue;
            }

            try {
                futures.set(i, CompletableFuture.completedFuture(runTask(ctx, plan, true, Utils.uuid())));
            } catch (Throwable e) {
                // runTask 抛出时该下标会留 null，在结果里变成缺少外壳的一行
                futures.set(i, CompletableFuture.completedFuture(
                        formatTaskResp(plan.op, false, "ERROR: 任务执行失败: " + rootMessage(e), true)));
            } finally {
                releaseQuietly(plan.slot);
            }
        }
    }

    /**
     * 并行分派：先在调用线程非阻塞抢槽，抢到就提交；抢不到的统一进轮询队列。
     *
     * <p>不按下标顺序阻塞：否则一个低配额模型排在前头就会挡住后面所有模型，
     * 整批吞吐退化为该模型的串行度。轮询时只要有一项抢到就立刻再抢一轮。</p>
     */
    private void dispatchParallel(List<TaskPlan> plans, List<CompletableFuture<String>> futures,
                                  MultitaskContext ctx, FluxSink<AgentEvent> sink) {
        final List<Integer> waiting = new ArrayList<>(plans.size());

        for (int i = 0; i < plans.size(); i++) {
            TaskPlan plan = plans.get(i);

            if (isCancelled(sink)) {
                futures.set(i, notStarted(plan.op));
            } else if (plan.slot == null || plan.slot.tryAcquire()) {
                submitTask(i, plan, futures, ctx);
            } else {
                waiting.add(i);
            }
        }

        final long start = System.currentTimeMillis();
        boolean warned = false;

        while (!waiting.isEmpty()) {
            List<Integer> ready = tryAcquireReady(waiting, plans);

            for (int i : ready) {
                submitTask(i, plans.get(i), futures, ctx);
            }

            if (waiting.isEmpty()) {
                return;
            }

            if (!ready.isEmpty()) {
                // 刚有任务结束，可能还有余量：立刻再抢一轮，不必等满一个节拍
                continue;
            }

            if (isCancelled(sink)) {
                markNotStarted(plans, waiting, futures);
                return;
            }

            if (!warned && System.currentTimeMillis() - start >= SLOT_WARN_MS) {
                warned = true;
                LOG.warn("还有 {} 个子任务在排队：目标模型配额已满，或已有长任务占着槽位（子任务无超时）；"
                        + "可调高模型级 AiConfig#concurrency", waiting.size());
            }

            try {
                Thread.sleep(SLOT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markNotStarted(plans, waiting, futures);
                return;
            }
        }
    }

    /** 挑出此刻能取到槽位的任务并从 {@code waiting} 移除；槽位为 null 视为立即可用 */
    static List<Integer> tryAcquireReady(List<Integer> waiting, List<TaskPlan> plans) {
        List<Integer> ready = new ArrayList<>();
        Iterator<Integer> it = waiting.iterator();

        while (it.hasNext()) {
            int i = it.next();
            TaskPlan plan = plans.get(i);

            if (plan.slot == null || plan.slot.tryAcquire()) {
                it.remove();
                ready.add(i);
            }
        }

        return ready;
    }

    private void markNotStarted(List<TaskPlan> plans, List<Integer> indexes,
                                List<CompletableFuture<String>> futures) {
        for (int i : indexes) {
            futures.set(i, notStarted(plans.get(i).op));
        }
    }

    /**
     * 提交已取到槽位的任务到私有执行器，结果按下标写回；槽位由执行任务结束时归还。
     */
    private void submitTask(int index, TaskPlan plan,
                            List<CompletableFuture<String>> futures, MultitaskContext ctx) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        futures.set(index, future);

        try {
            taskExecutor.execute(() -> {
                try {
                    future.complete(runTask(ctx, plan, true, Utils.uuid()));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                } finally {
                    releaseQuietly(plan.slot);
                }
            });
        } catch (Throwable e) {
            // 不能只 catch RejectedExecutionException：建线程时 OOM 是 Error，
            // 会漏掉已到手的许可，该模型后续全部卡死
            releaseQuietly(plan.slot);
            future.complete(formatTaskResp(plan.op, false, "ERROR: 任务提交失败（执行器已关闭）", true));
        }
    }

    // ========== 聚合 ==========

    /**
     * 按下标顺序聚合结果。
     *
     * <p>不用 {@code join()}：它不可中断，用户按停止后只能逐个等到底。改为节拍轮询 {@code get}，
     * 每轮检查取消；一旦取消或中断就放弃等待后续任务。<b>没有截止时间</b>——
     * 超时只是节拍，不当成失败（子任务可能跑数天）。</p>
     */
    private String aggregate(List<TaskPlan> plans, List<CompletableFuture<String>> futures, MultitaskContext ctx) {
        StringBuilder compositeResult = new StringBuilder();
        compositeResult.append("<multitask_results>\n");

        final FluxSink<AgentEvent> sink = streamSinkOf(ctx.parentTrace);

        // 非 null 表示已放弃等待：当前及剩余任务统一按该文案返回
        String abortedNote = null;

        for (int i = 0; i < futures.size(); i++) {
            if (abortedNote != null) {
                compositeResult.append(abortedNote).append("\n");
                continue;
            }

            CompletableFuture<String> f = futures.get(i);

            if (f == null) {
                compositeResult.append(formatTaskResp(plans.get(i).op, false,
                        "ERROR: 子任务未启动", true)).append("\n");
                continue;
            }

            String line = null;

            while (line == null) {
                if (isCancelled(sink)) {
                    abortedNote = "ERROR: 子任务未完成（主流程已取消，未等待）";
                    break;
                }

                try {
                    String v = f.get(SLOT_POLL_MS, TimeUnit.MILLISECONDS);
                    line = (v == null) ? "" : v;
                } catch (TimeoutException e) {
                    // 轮询节拍：任务还在跑，下一轮重新检查取消
                } catch (ExecutionException e) {
                    line = "ERROR: 子任务未完成（失败或被中断）: " + rootMessage(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    abortedNote = "ERROR: 子任务未完成（已中断，未等待）";
                    // 必须跳出：中断标志已置回，下一轮 get 会立即再抛，不退出就是零间隔死循环
                    break;
                }
            }

            compositeResult.append(abortedNote != null ? abortedNote : line).append("\n");
        }

        compositeResult.append("</multitask_results>");

        if (ctx.rateLimited.get() || ctx.upstreamUnavailable.get() || ctx.timedOut.get()) {
            compositeResult.append("\n<multitask_advice>\n");
            if (ctx.rateLimited.get()) {
                compositeResult.append("检测到模型服务限流，可能来自中转网关或上游模型服务。后续请优先使用 task 串行执行；如必须使用 multitask，可在模型配置中调低 `concurrency`（未配置时调低引擎的 `subagentDefaultConcurrency`）。\n");
            } else if (ctx.upstreamUnavailable.get()) {
                compositeResult.append("检测到中转网关或上游模型服务暂时不可用，可能与上游限流、服务过载或网关异常有关。请稍后重试，并适当调低模型配置的 `concurrency` 或引擎的 `subagentDefaultConcurrency`。\n");
            } else {
                compositeResult.append("模型服务响应超时。后续请适当调低模型配置的 `concurrency` 或引擎的 `subagentDefaultConcurrency`，必要时改用 task 串行执行。\n");
            }
            compositeResult.append("</multitask_advice>");
        }

        return compositeResult.toString();
    }

    /** 一次调用的共享上下文（避免 runTask 参数过长）；错误统计标记可为 null（单任务路径不统计） */
    private static final class MultitaskContext {
        private final ReActTrace parentTrace;
        private final String cwd;
        private final String sessionId;
        private final AgentSession parentSession;
        private final int total;
        private final AtomicBoolean rateLimited;
        private final AtomicBoolean timedOut;
        private final AtomicBoolean upstreamUnavailable;

        MultitaskContext(ReActTrace parentTrace, String cwd, String sessionId, AgentSession parentSession,
                         int total, AtomicBoolean rateLimited, AtomicBoolean timedOut,
                         AtomicBoolean upstreamUnavailable) {
            this.parentTrace = parentTrace;
            this.cwd = cwd;
            this.sessionId = sessionId;
            this.parentSession = parentSession;
            this.total = total;
            this.rateLimited = rateLimited;
            this.timedOut = timedOut;
            this.upstreamUnavailable = upstreamUnavailable;
        }
    }

    /** 未启动的任务结果（保持总数与下标不变，避免结果错位） */
    private CompletableFuture<String> notStarted(MultiTaskOp task) {
        return CompletableFuture.completedFuture(
                formatTaskResp(task, false, notStartedReason(), true));
    }

    /** 区分「用户取消」与「执行中断」，否则事后无法判断是按了停止还是被中断 */
    private static String notStartedReason() {
        return Thread.currentThread().isInterrupted() ? "执行被中断，任务未启动" : "主流程已取消，任务未启动";
    }

    /**
     * 解析生效的并发上限：模型配置优先，未设置（0）时用默认值。
     *
     * <p>与 {@code ContextCompressionInterceptor#finalContextLength} 同构，不存在
     * 「两级取小」那种「配了 8 却只跑 5」的静默降级。</p>
     *
     * @return 恒为正整数
     */
    static int resolveConcurrency(int modelConcurrency, int defaultConcurrency) {
        if (modelConcurrency > 0) {
            return modelConcurrency;
        }

        // 防御归一：配置侧已保证 ≥ 1，这里只让纯函数自洽
        return defaultConcurrency > 0 ? defaultConcurrency : DEFAULT_CONCURRENCY;
    }

    /**
     * 解析子任务实际会使用的模型配置。
     *
     * @return 可能为 null（模型全部禁用且无默认项）
     */
    private ChatConfig resolveTaskModelConfig(AgentDefinition agentDefinition, String sessionModel) {
        final String selectedModel = Assert.isEmpty(agentDefinition.getModel())
                ? sessionModel : agentDefinition.getModel();

        return engine.getModelOrDef(selectedModel);
    }

    /**
     * 某模型配置下的生效并发上限。
     *
     * @return {@link #CAP_NONE} 表示无槽位（配置不可解析，任务会立即失败）
     */
    int finalConcurrency(ChatConfig config) {
        return config == null ? CAP_NONE : resolveConcurrency(config.getConcurrency(), defaultConcurrency);
    }

    /**
     * 槽位分组键：模型名优先，无名配置退回实例身份（否则所有无名配置会被误合并成一份配额）。
     */
    private static Object slotKeyOf(ChatConfig config) {
        String name = config.getNameOrModel();
        return (name == null) ? config : name;
    }

    /**
     * 取模型对应信号量；配置不可解析时返回 null。
     *
     * <p>上限变更时换新信号量而不复用：代价是变更瞬间可能短暂超额，
     * 但省掉了一套「可调上限信号量 + 欠账」机制；正常运行期上限不会变。</p>
     *
     * @param limit 已解析的生效上限（调用方算好，避免重复读 volatile）
     */
    Semaphore semaphoreOf(ChatConfig config, int limit) {
        if (config == null) {
            return null;
        }

        return modelSlots.compute(slotKeyOf(config),
                (k, old) -> (old == null || old.limit != limit) ? new ModelSlot(limit) : old).semaphore;
    }

    /** 模型级槽位：limit 用于检测上限变更 */
    private static final class ModelSlot {
        final int limit;
        final Semaphore semaphore;

        ModelSlot(int limit) {
            this.limit = limit;
            this.semaphore = new Semaphore(limit);
        }
    }

    /**
     * 轮询式获取槽位：每 {@link #SLOT_POLL_MS} 醒一次检查取消，保证按停止后不会僵等到任务跑完。
     *
     * <p>在调用线程阻塞（执行器里永远只有真正在跑的任务）；只有模型级一把信号量，无死锁面。</p>
     *
     * @return true 成功（调用方必须 releaseQuietly）；false 已取消或被中断
     */
    boolean acquirePolling(Semaphore sem, FluxSink<AgentEvent> sink) {
        final long start = System.currentTimeMillis();
        boolean warned = false;

        while (true) {
            try {
                if (sem.tryAcquire(SLOT_POLL_MS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            if (isCancelled(sink)) {
                return false;
            }

            if (!warned && System.currentTimeMillis() - start >= SLOT_WARN_MS) {
                warned = true;
                LOG.warn("子代理并发槽位排队超过 {} 秒仍未取到：该模型配额已满，"
                                + "或已有长任务占着槽位（子任务无超时）；可调高模型级 AiConfig#concurrency",
                        SLOT_WARN_MS / 1000);
            }
        }
    }

    static void releaseQuietly(Semaphore sem) {
        if (sem != null) {
            sem.release();
        }
    }

    private static boolean isCancelled(FluxSink<AgentEvent> sink) {
        return sink != null && sink.isCancelled();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private String runTask(MultitaskContext ctx, TaskPlan plan, boolean isMultitask, String taskId) {
        if (plan.invalidReason != null) {
            return formatTaskResp(plan.op, false, plan.invalidReason, isMultitask);
        }

        if (plan.agentDefinition == null) {
            // 保底：统一走外壳，不返回裸文本（否则会混进 <multitask_results> 里错位）
            return formatTaskResp(plan.op, false, unknownAgentReason(plan.op), isMultitask);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务开始[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name, ONode.serialize(plan.op));
        }

        try {
            // 标记委派深度：本线程内再触发 task/multitask 会被拒绝
            TASK_DEPTH.set(Boolean.TRUE);

            ReActAgent agent = engine.getAgentBuilder(plan.agentDefinition, plan.modelName).build();
            final AgentSession session = InMemoryAgentSession.of(agent.name());

            Prompt originalPrompt = Prompt.of(plan.op.prompt);
            originalPrompt.attrs().computeIfAbsent(ChatSession.ATTR_SESSIONID,
                    k -> session.getSessionId());

            RunEndEvent agentEnd;

            if (ctx.parentTrace == null || ctx.parentTrace.getOptions() == null
                    || ctx.parentTrace.getOptions().getStreamSink() == null) {
                // 同步模式
                agentEnd = (RunEndEvent) agent.prompt(originalPrompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, ctx.cwd);
                            o.toolContextPut(ChatSession.ATTR_SESSIONID, ctx.sessionId);
                        })
                        .stream()
                        .doOnNext(chunk -> countLlmError(chunk, ctx.rateLimited, ctx.timedOut,
                                ctx.upstreamUnavailable))
                        .blockLast();
            } else {
                // 流式模式
                final FluxSink<AgentEvent> sink = ctx.parentTrace.getOptions().getStreamSink();

                agentEnd = (RunEndEvent) agent.prompt(originalPrompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, ctx.cwd);
                            o.toolContextPut(ChatSession.ATTR_SESSIONID, ctx.sessionId);
                        })
                        .stream()
                        .takeUntil(r -> sink.isCancelled())
                        .doOnNext(chunk -> {
                            countLlmError(chunk, ctx.rateLimited, ctx.timedOut, ctx.upstreamUnavailable);
                            // 父流已取消时停止向主会话灌 chunk
                            if (sink.isCancelled()) {
                                return;
                            }
                            // multitask 并发时串行化 sink.next，避免非线程安全的 FluxSink 丢事件/乱序
                            emitTaskChunk(sink, new TaskWrapEvent(ctx.parentTrace, taskId, plan.op,
                                    isMultitask, chunk));
                        })
                        .blockLast();
            }

            if (agentEnd == null) {
                FluxSink<AgentEvent> sink = streamSinkOf(ctx.parentTrace);
                if (isCancelled(sink)) {
                    throw new IllegalStateException("主代理已暂停");
                }
                throw new IllegalStateException("子代理未正常完成：未收到正常响应");
            }

            countLlmErrorType(agentEnd.getLlmErrorType(), ctx.rateLimited, ctx.timedOut,
                    ctx.upstreamUnavailable);

            if (ctx.parentTrace != null) {
                ctx.parentTrace.getMetrics().addMetrics(agentEnd.getMetrics());
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("任务成功[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name,
                        plan.op.description);
            }

            return formatTaskResp(plan.op, true, agentEnd.getText(), isMultitask);
        } catch (Throwable e) {
            LOG.error("任务失败[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name,
                    e.getMessage(), e);
            countLlmErrorType(LlmErrorClassifier.classify(e), ctx.rateLimited, ctx.timedOut,
                    ctx.upstreamUnavailable);

            return formatTaskResp(plan.op, false, String.format("ERROR: 任务执行失败: %s", e.getMessage()),
                    isMultitask);
        } finally {
            // 必须清理：池线程会被复用，残留标记会误伤后续任务
            TASK_DEPTH.remove();
        }
    }

    private static void countLlmError(AgentEvent chunk,
                                      AtomicBoolean rateLimited,
                                      AtomicBoolean timedOut,
                                      AtomicBoolean upstreamUnavailable) {
        if (!(chunk instanceof LlmErrorEvent)) {
            return;
        }
        countLlmErrorType(((LlmErrorEvent) chunk).getErrorType(), rateLimited, timedOut, upstreamUnavailable);
    }

    /** 非流式子代理不会收到 LlmErrorEvent，从异常本身补充统计，避免并发建议丢失 */
    private static void countLlmErrorType(LlmErrorEvent.Type type,
                                          AtomicBoolean rateLimited,
                                          AtomicBoolean timedOut,
                                          AtomicBoolean upstreamUnavailable) {
        if (type == null || rateLimited == null || timedOut == null || upstreamUnavailable == null) {
            return;
        }
        if (type == LlmErrorEvent.Type.RATE_LIMIT) {
            rateLimited.set(true);
        } else if (type == LlmErrorEvent.Type.TIMEOUT) {
            timedOut.set(true);
        } else if (type == LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE) {
            upstreamUnavailable.set(true);
        }
    }

    /** 向父会话 sink 推送子代理 chunk；并发时对同一 sink 加锁，保证 next 串行 */
    private void emitTaskChunk(FluxSink<AgentEvent> sink, TaskWrapEvent wrapChuck) {
        synchronized (sink) {
            if (!sink.isCancelled()) {
                sink.next(wrapChuck);
            }
        }
    }

    String formatTaskResp(MultiTaskOp task, boolean successful, String result, boolean isMultitask) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_result>");
        if (isMultitask) {
            buf.append("<index>").append(task.index).append("</index>");
        }
        buf.append("<description>").append(escapeXml(task.description)).append("</description>");
        buf.append("<agent_name>").append(escapeXml(task.agent_name)).append("</agent_name>");
        buf.append("<result_status>").append(successful ? "success" : "failure").append("</result_status>");
        buf.append("<result_content><![CDATA[").append(toCdata(result)).append("]]></result_content>");
        buf.append("</task_result>");

        return buf.toString();
    }

    /** description / agent_name 同样来自模型输出，不转义会让 {@code <}、{@code &} 破坏外层结构 */
    private static String escapeXml(String text) {
        if (text == null) {
            return "";
        }

        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 塞进 CDATA：先截断、再处理终止符。
     *
     * <p>顺序不能反——先转义再截断可能把 {@code ]]>} 的转义产物切成半个，反而提前闭合。</p>
     */
    private static String toCdata(String text) {
        if (text == null) {
            return "";
        }

        String body = text;
        if (body.length() > MAX_RESULT_CHARS) {
            int cut = MAX_RESULT_CHARS;
            // 不要把代理对切成单个 char（会输出乱码）
            if (Character.isHighSurrogate(body.charAt(cut - 1))) {
                cut--;
            }
            body = body.substring(0, cut)
                    + "\n...[内容过长已截断，原始长度 " + text.length() + " 字符]";
        }

        // CDATA 里只有终止符 ]]> 需要拆开，其余字符（含 < &）无需转义
        return body.replace("]]>", "]]]]><![CDATA[>");
    }

    public static class SingleTaskOp {
        @Param(name = "agent_name", description = "子代理名称")
        public String agent_name;
        @Param(name = "prompt", description = "发给子代理的详细指令。由于子代理是无状态的（上下文隔离），必须在此提供任务所需的所有背景信息、具体要求及预期输出格式。")
        public String prompt;
        @Param(name = "description", description = "任务内容的极简摘要（如：'重构用户认证逻辑'）。该描述将作为标签出现在执行日志和结果摘要中，用于快速识别任务意图。")
        public String description;

        @Override
        public String toString() {
            return "SingleTaskOp{" +
                    "agent_name='" + agent_name + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }

    /**
     * 任务定义
     */
    public static class MultiTaskOp extends SingleTaskOp {
        @Param(name = "index",
                description = "任务唯一序号，每个任务分配唯一的递增整数（从1开始），以便匹配返回结果",
                defaultValue = "1")
        public int index = 1;

        @Override
        public String toString() {
            return "MultiTaskOp{" +
                    "index='" + index + '\'' +
                    ", agent_name='" + agent_name + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }
}
