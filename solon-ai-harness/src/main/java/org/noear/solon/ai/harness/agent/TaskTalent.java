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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 子代理才能
 *
 * 将子代理能力暴露为可调用的工具（Claude Code Subagent 类似实现）
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(TaskTalent.class);

    public static final String TOOL_TASK = "task";
    public static final String TOOL_MULTITASK = "multitask";

    /**
     * 委派深度标记（防嵌套死锁）：
     * 子代理任务线程内可见，子代理若再调用 task/multitask 会被拒绝
     */
    private static final ThreadLocal<Boolean> TASK_DEPTH = new ThreadLocal<>();

    /** 等待槽位时的轮询间隔：保证取消能及时响应，又不至于空转 */
    private static final long SLOT_POLL_MS = 200L;

    private final HarnessEngine engine;

    /**
     * 模型级并行槽位：键为 ChatConfig 实例身份（ChatConfig 未重写 equals/hashCode）。
     *
     * <p>按实例分组而非按模型名，避免同名不同配置（不同 apiKey/网关）被误合并限流。</p>
     */
    private final Map<ChatConfig, ModelSlot> modelSlots = new ConcurrentHashMap<>();

    /**
     * 子代理任务默认并发上限：模型未提供 {@code AiConfig#getConcurrency()}（0 = 未设置）时使用。
     *
     * <p>默认值的语义与 {@code ContextCompressionInterceptor#setDefaultContextLength} 同构：
     * 仅在配置侧未给出时生效，一旦模型显式配置了并发数就以模型配置为准。
     * 设为 0 表示整体不限制。</p>
     */
    private volatile int defaultConcurrency = 5;

    /**
     * 子任务执行器（引擎私有，与 RunUtil.io 全局共享池隔离）。
     *
     * <p>为何不复用 {@code RunUtil.io()}：它是 JVM 全局共享池，其他模块（如 goal 调度）也会占用。
     * 若 multitask 的分派线程本身就跑在该池上，等待槽位会把池线程全部占死，
     * 拿到槽位的任务永远等不到线程执行，许可永不释放，形成死锁。</p>
     *
     * <p>线程为守护线程，随 JVM 退出自动回收。</p>
     */
    private final ExecutorService taskExecutor;

    public TaskTalent(HarnessEngine engine) {
        this.engine = engine;
        // 必须是 SynchronousQueue（Cached 语义）：若用无界队列配 core=0，
        // ThreadPoolExecutor 只在 "workerCount < corePoolSize" 时才建新线程，
        // 而 core=0 永不成立，后续任务全部入队排队，实际并发恒为 1。
        // 并发量已由信号量卡住（获许可才提交），因此这里的线程数天然有界。
        this.taskExecutor = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new TaskThreadFactory());
    }

    /**
     * @param defaultConcurrency 子代理任务默认并发上限；不能被负数
     * @see org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor#setDefaultContextLength
     */
    public void setDefaultConcurrency(int defaultConcurrency) {
        if (defaultConcurrency < 0) {
            throw new IllegalArgumentException("defaultConcurrency must be >= 0: " + defaultConcurrency);
        }
        this.defaultConcurrency = defaultConcurrency;
    }

    /**
     * 子代理任务默认并发上限（模型未配置时生效；0 = 不限制）
     */
    public int getDefaultConcurrency() {
        return defaultConcurrency;
    }

    /**
     * 释放本才能持有的执行器线程（可选调用；线程为守护线程，不调用也不会阻塞 JVM 退出）
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
        sb.append("1. **优先并行**: 当任务可以被拆分为互不干扰的独立单元（例如：不同的模块开发、多路搜索）时，可使用 `multitask` 节省时间。");
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

        MultiTaskOp taskOp = new MultiTaskOp();
        taskOp.agent_name = taskSpec.agent_name;
        taskOp.description = taskSpec.description;
        taskOp.prompt = taskSpec.prompt;

        return runTask(__parentTrace, __cwd, __sessionId, __parentSession, taskOp, 1, false, Utils.uuid(), null, null, null);
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

        // 防嵌套死锁：子代理内再委派会形成"共享池自等待"死锁
        if (TASK_DEPTH.get() != null) {
            return "ERROR: 子代理不允许再委派子任务（最多一层）。请直接完成当前任务；如需并行，请返回后由主代理统一编排。";
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        final FluxSink<AgentEvent> parentSink = (__parentTrace != null && __parentTrace.getOptions() != null)
                ? __parentTrace.getOptions().getStreamSink() : null;

        final int total = tasks.size();

        // 逐任务解析实际会用的模型：子任务可能各自路由到不同模型，
        // 限流必须按任务自身的模型取槽，否则一个低配额模型会拖垮整批
        final String sessionModel = __parentSession.getContext()
                .getAs(HarnessEngine.CTX_MODEL_SELECTED);

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务接收[{}]: {} 个子任务，子代理默认并发上限 {}", __sessionId, total,
                    defaultConcurrency > 0 ? String.valueOf(defaultConcurrency) : "不限");
        }

        final List<CompletableFuture<String>> futures = new ArrayList<>(total);
        final AtomicBoolean rateLimited = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicBoolean upstreamUnavailable = new AtomicBoolean();

        if (isSerialOnly(tasks, sessionModel)) {
            // 串行快速路径：生效并行度恒为 1 时直接在调用线程逐个执行，
            // 省去每任务一次线程投递，且与 task 语义完全一致。
            // 注意：判定条件是「所有任务」上限都为 1，不能是「任一任务」——
            // 否则一个 cap=1 的子代理会把整批拖成串行，正是本次改造要消除的缺陷
            for (MultiTaskOp task : tasks) {
                if (isCancelled(parentSink)) {
                    futures.add(CompletableFuture.completedFuture(
                            formatTaskResp(task, false, "主流程已取消，任务未启动", true)));
                    continue;
                }

                futures.add(CompletableFuture.completedFuture(
                        runTask(__parentTrace, __cwd, __sessionId, __parentSession, task, total, true,
                                Utils.uuid(), rateLimited, timedOut, upstreamUnavailable)));
            }
        } else {
            // 提交前限流：在调用线程取槽，取到才提交到私有执行器。
            // 这样执行器里永远只有真正在跑的任务，不存在「占着线程等许可」
            for (MultiTaskOp task : tasks) {
                if (isCancelled(parentSink)) {
                    futures.add(CompletableFuture.completedFuture(
                            formatTaskResp(task, false, "主流程已取消，任务未启动", true)));
                    continue;
                }

                AgentDefinition agentDefinition = engine.getAgentManager().getAgent(task.agent_name);
                if (agentDefinition == null) {
                    // 未知子代理：不占槽位，交由 runTask 返回原有的错误文案
                    futures.add(CompletableFuture.completedFuture(
                            runTask(__parentTrace, __cwd, __sessionId, __parentSession, task, total, true,
                                    Utils.uuid(), rateLimited, timedOut, upstreamUnavailable)));
                    continue;
                }

                Semaphore slot = semaphoreOf(resolveTaskModelConfig(agentDefinition, sessionModel));

                if (slot != null && !acquirePolling(slot, parentSink)) {
                    futures.add(CompletableFuture.completedFuture(
                            formatTaskResp(task, false, "主流程已取消，任务未启动", true)));
                    continue;
                }

                final CompletableFuture<String> future = new CompletableFuture<>();
                futures.add(future);

                try {
                    taskExecutor.execute(() -> {
                        try {
                            future.complete(runTask(__parentTrace, __cwd, __sessionId, __parentSession, task,
                                    total, true, Utils.uuid(), rateLimited, timedOut, upstreamUnavailable));
                        } catch (Throwable e) {
                            future.completeExceptionally(e);
                        } finally {
                            releaseQuietly(slot);
                        }
                    });
                } catch (Throwable e) {
                    // 不能只 catch RejectedExecutionException：创建线程时抛 OutOfMemoryError 是 Error，
                    // 那会导致已到手的许可永久漏掉，该模型后续全部卡死
                    releaseQuietly(slot);
                    future.complete(formatTaskResp(task, false, "ERROR: 任务提交失败（执行器已关闭）", true));
                }
            }
        }

        // 逐任务聚合：单个失败不影响其他任务的结果回收，失败原因逐任务回流
        String result;
        {
            StringBuilder compositeResult = new StringBuilder();
            compositeResult.append("<multitask_results>\n");
            for (CompletableFuture<String> f : futures) {
                try {
                    compositeResult.append(f.join()).append("\n");
                } catch (Throwable t) {
                    compositeResult.append("ERROR: 子任务未完成（失败或被中断）: ")
                            .append(rootMessage(t)).append("\n");
                }
            }
            compositeResult.append("</multitask_results>");
            if (rateLimited.get() || upstreamUnavailable.get() || timedOut.get()) {
                compositeResult.append("\n<multitask_advice>\n");
                if (rateLimited.get()) {
                    compositeResult.append("检测到模型服务限流，可能来自中转网关或上游模型服务。后续请优先使用 task 串行执行；如必须使用 multitask，可在模型配置中调低 `concurrency`（未配置时调低引擎的 `subagentDefaultConcurrency`）。\n");
                } else if (upstreamUnavailable.get()) {
                    compositeResult.append("检测到中转网关或上游模型服务暂时不可用，可能与上游限流、服务过载或网关异常有关。请稍后重试，并适当调低模型配置的 `concurrency` 或引擎的 `subagentDefaultConcurrency`。\n");
                } else {
                    compositeResult.append("模型服务响应超时。后续请适当调低模型配置的 `concurrency` 或引擎的 `subagentDefaultConcurrency`，必要时改用 task 串行执行。\n");
                }
                compositeResult.append("</multitask_advice>");
            }
            result = compositeResult.toString();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务完成[{}]：{} 个子任务结束", __sessionId, total);
        }

        return result;
    }

    /**
     * 解析生效的并发上限：模型配置优先，未设置（0）时回退到子代理默认值。
     *
     * <p>与 {@code ContextCompressionInterceptor#finalContextLength} 同构：先取默认值，
     * 配置侧给出了才覆盖。因此不存在「两级同时生效取小」那种
     * 「模型配了 8 却只跑 5」的静默降级。</p>
     *
     * @param modelConcurrency   模型级配置（0 = 未设置）
     * @param defaultConcurrency 子代理默认值（0 = 不限制）
     * @return 正数 = 生效上限；0 = 不限制
     */
    static int resolveConcurrency(int modelConcurrency, int defaultConcurrency) {
        if (modelConcurrency > 0) {
            return modelConcurrency;
        }

        return Math.max(defaultConcurrency, 0);
    }

    /**
     * 解析某子任务实际会使用的模型配置。
     *
     * <p>必须与 {@code AgentFactory.create(engine, agentDefinition, sessionModel)} 的解析链
     * 完全一致：AgentDefinition.model 非空则用之，否则用会话选中模型，否则用默认模型。
     * 两者一旦分叉，限流用的模型和真正跑的模型会对不上，限流静默失效而不报错。</p>
     *
     * @return 可能为 null（模型全部禁用且无默认项）
     */
    private ChatConfig resolveTaskModelConfig(AgentDefinition agentDefinition, String sessionModel) {
        final String selectedModel;

        if (Assert.isEmpty(agentDefinition.getModel())) {
            selectedModel = sessionModel;
        } else {
            selectedModel = agentDefinition.getModel();
        }

        return engine.getModelOrDef(selectedModel);
    }

    /**
     * 某模型配置下的生效并发上限（已做好模型优先、默认回退）
     *
     * @return 0 表示不限制（含模型不可解析的情况：任务本身会立即失败，不构成并发压力）
     */
    private int finalConcurrency(ChatConfig config) {
        if (config == null) {
            return 0;
        }

        return resolveConcurrency(config.getConcurrency(), defaultConcurrency);
    }

    /**
     * 取模型对应信号量；生效上限为 0（不限）或配置不可解析时返回 null 表示不限制
     */
    private Semaphore semaphoreOf(ChatConfig config) {
        if (config == null) {
            return null;
        }

        final int limit = finalConcurrency(config);
        if (limit <= 0) {
            return null;
        }

        ModelSlot slot = modelSlots.get(config);
        if (slot != null && slot.limit == limit) {
            return slot.semaphore;
        }

        // 两个线程同时重建时只有一个生效（compute 保证原子性），不会许可翻倍；
        // 上限变更时换新信号量：旧信号量由已在跑的任务释放后自然回收（旧新短暂共存属可接受偏差）
        final ModelSlot created = new ModelSlot(limit);
        ModelSlot exist = modelSlots.compute(config, (k, old) -> (old != null && old.limit == limit) ? old : created);

        return exist.semaphore;
    }

    /**
     * 轮询式获取：每 {@link #SLOT_POLL_MS} 毫秒醒一次检查取消，
     * 保证用户按停止后不会僵在原地等到任务跑完
     *
     * <p>阻塞在调用线程（而非任务线程）：执行器里永远只有真正在跑的任务。
     * 只有模型级一把信号量，不存在多级取位的顺序问题，无死锁面。</p>
     *
     * @return true 成功（调用方必须在任务结束时 releaseQuietly）；false 主流程已取消或被中断
     */
    private boolean acquirePolling(Semaphore sem, FluxSink<AgentEvent> sink) {
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
        }
    }

    private static void releaseQuietly(Semaphore sem) {
        if (sem != null) {
            sem.release();
        }
    }

    private static boolean isCancelled(FluxSink<AgentEvent> sink) {
        return sink != null && sink.isCancelled();
    }

    /**
     * 判定是否只能串行：所有任务目标模型的<b>生效</b>上限都为 1。
     *
     * <p>两个关键点：</p>
     * <ol>
     *   <li>必须是「全部」而非「任一」：任一成立时，一个 cap=1 的子代理就会把整批拖成串行，
     *       恰好是本次改造要消除的缺陷（混合模型应各自受限、互不拖累）。</li>
     *   <li>必须用生效上限（已做模型优先、默认回退），不能用裸的 {@code getConcurrency()}：
     *       否则模型未配置、默认值为 1 时会被当成「未设置/不限」而错走并行路径。</li>
     * </ol>
     */
    private boolean isSerialOnly(List<MultiTaskOp> tasks, String sessionModel) {
        List<Integer> effectiveCaps = new ArrayList<>(tasks.size());

        for (MultiTaskOp task : tasks) {
            AgentDefinition agentDefinition = engine.getAgentManager().getAgent(task.agent_name);
            if (agentDefinition == null) {
                continue;
            }

            effectiveCaps.add(finalConcurrency(resolveTaskModelConfig(agentDefinition, sessionModel)));
        }

        return isSerialOnlyByCaps(effectiveCaps);
    }

    /**
     * 串行判定的纯函数形式（抽出来便于锁定「全部而非任一」这条关键语义）。
     *
     * @param effectiveCaps 各任务目标模型的生效并发上限（0 = 不限制）
     * @return true = 生效并行度恒为 1，可走调用线程串行快路径
     */
    static boolean isSerialOnlyByCaps(List<Integer> effectiveCaps) {
        if (effectiveCaps.isEmpty()) {
            // 全部是未知子代理：没有可判定对象，交给常规路径返回错误文案
            return false;
        }

        for (Integer cap : effectiveCaps) {
            if (cap == null || cap != 1) {
                return false;
            }
        }

        return true;
    }

    /**
     * 模型级槽位：limit 与信号量绑定在一起，上限变更时可原子地整体替换。
     *
     * <p>不能从 {@code availablePermits()} 反推上限：任务运行中许可被占时，
     * 可用数必然小于上限，会误判为“上限变了”而反复重建，导致限流失效。</p>
     *
     * <p>limit 存的是<b>生效上限</b>（已含模型优先、默认回退），而非模型裸配置值。</p>
     */
    private static final class ModelSlot {
        private final int limit;
        private final Semaphore semaphore;

        ModelSlot(int limit) {
            this.limit = limit;
            this.semaphore = new Semaphore(limit);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private String runTask(ReActTrace __parentTrace, String __cwd, String __sessionId, AgentSession __parentSession, MultiTaskOp task, int count, boolean isMultitask, String taskId, AtomicBoolean rateLimited, AtomicBoolean timedOut, AtomicBoolean upstreamUnavailable) {
        AgentDefinition agentDefinition = engine.getAgentManager().getAgent(task.agent_name);
        if (agentDefinition == null) {
            return "ERROR: 未知的子代理类型 '" + task.agent_name + "'。";
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务开始[{}/{} - {}]: {}", task.index, count, task.agent_name, ONode.serialize(task));
        }

        try {
            // 标记委派深度：本线程内再触发 task/multitask 会被拒绝
            TASK_DEPTH.set(Boolean.TRUE);

            String modelSelected = __parentSession.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);

            ReActAgent agent = engine.getAgentBuilder(agentDefinition, modelSelected).build();
            final AgentSession session = InMemoryAgentSession.of(agent.name());

            String result = null;
            Prompt originalPrompt = Prompt.of(task.prompt);
            originalPrompt.attrs().computeIfAbsent(ChatSession.ATTR_SESSIONID,
                    k -> session.getSessionId());

            AtomicReference<Throwable> errRef = new AtomicReference<>();

            if (__parentTrace == null || __parentTrace.getOptions() == null || __parentTrace.getOptions().getStreamSink() == null) {
                // 同步模式
                RunEndEvent agentEnd = (RunEndEvent) agent.prompt(originalPrompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, __cwd);
                            o.toolContextPut(ChatSession.ATTR_SESSIONID, __sessionId);
                        })
                        .stream()
                        .doOnNext(chunk -> countLlmError(chunk, rateLimited, timedOut, upstreamUnavailable))
                        .doOnError(err -> {
                            errRef.set(err);
                        })
                        .blockLast();

                if (errRef.get() != null) {
                    throw errRef.get();
                }

                if (agentEnd == null) {
                    throw new IllegalStateException("子代理未正常完成：未收到正常响应");
                }
                countLlmErrorType(agentEnd.getLlmErrorType(),
                        rateLimited, timedOut, upstreamUnavailable);

                if (__parentTrace != null) {
                    __parentTrace.getMetrics().addMetrics(agentEnd.getMetrics());
                }

                result = agentEnd.getText();
            } else {
                // 流式模式
                final FluxSink<AgentEvent> sink = __parentTrace.getOptions().getStreamSink();

                RunEndEvent agentEnd = (RunEndEvent) agent.prompt(originalPrompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, __cwd);
                            o.toolContextPut(ChatSession.ATTR_SESSIONID, __sessionId);
                        })
                        .stream()
                        .takeUntil(r -> sink.isCancelled())
                        .doOnNext(chunk -> {
                            countLlmError(chunk, rateLimited, timedOut, upstreamUnavailable);
                            // 父流已取消时停止向主会话灌 chunk，避免取消后仍重复输出
                            if (sink.isCancelled()) {
                                return;
                            }
                            // multitask 并发时串行化 sink.next，避免非线程安全的 FluxSink 丢事件/乱序
                            emitTaskChunk(sink, new TaskWrapEvent(__parentTrace, taskId, task, isMultitask, chunk));
                        })
                        .doOnError(err -> {
                            errRef.set(err);
                        })
                        .blockLast();

                if (errRef.get() != null) {
                    throw errRef.get();
                }

                if (agentEnd == null) {
                    if (sink.isCancelled()) {
                        throw new IllegalStateException("主代理已暂停");
                    }
                    throw new IllegalStateException("子代理未正常完成：未收到正常响应");
                }
                countLlmErrorType(agentEnd.getLlmErrorType(),
                        rateLimited, timedOut, upstreamUnavailable);

                __parentTrace.getMetrics().addMetrics(agentEnd.getMetrics());
                result = agentEnd.getText();
            }


            if (LOG.isDebugEnabled()) {
                LOG.debug("任务成功[{}/{} - {}]: {}", task.index, count, task.agent_name, task.description);
            }

            return formatTaskResp(task, true, result, isMultitask);
        } catch (Throwable e) {
            LOG.error("任务失败[{}/{} - {}]: {}", task.index, count, task.agent_name, e.getMessage(), e);
            countLlmErrorType(LlmErrorClassifier.classify(e), rateLimited, timedOut, upstreamUnavailable);

            String result = String.format("ERROR: 任务执行失败: %s", e.getMessage());

            return formatTaskResp(task, false, result, isMultitask);
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

    /**
     * 非流式子代理不会收到 LlmErrorEvent，因此从异常本身补充统计，避免并发建议丢失。
     */
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

    /**
     * 向父会话 sink 推送子代理 chunk。
     * multitask 并发时对同一 sink 加锁，保证 next 串行。
     */
    private void emitTaskChunk(FluxSink<AgentEvent> sink, TaskWrapEvent wrapChuck) {
        synchronized (sink) {
            if (!sink.isCancelled()) {
                sink.next(wrapChuck);
            }
        }
    }

    private String formatTaskResp(MultiTaskOp task, boolean successful, String result, boolean isMultitask) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_result>");
        if (isMultitask) {
            buf.append("<index>").append(task.index).append("</index>");
        }
        buf.append("<description>").append(task.description).append("</description>");
        buf.append("<agent_name>").append(task.agent_name).append("</agent_name>");
        buf.append("<result_status>").append(successful ? "success" : "failure").append("</result_status>");
        buf.append("<result_content><![CDATA[").append(result != null ? result : "").append("]]></result_content>");
        buf.append("</task_result>");

        return buf.toString();
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