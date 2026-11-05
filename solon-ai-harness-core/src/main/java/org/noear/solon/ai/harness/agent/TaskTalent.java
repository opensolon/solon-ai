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

import org.noear.solon.ai.harness.session.SessionSelections;
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
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理（任务）才能：把子代理暴露为 {@code task} / {@code multitask} 工具。
 *
 * <p><b>并发契约</b>：上限<b>按调用</b>生效，取当前会话所选模型的 {@code AiConfig#concurrency}
 * （0 = 未设置）优先，否则取 {@link #DEFAULT_CONCURRENCY}，上界 {@link #MAX_CONCURRENCY}。
 * 恒为有限正整数，<b>不存在「不限制」</b>——那会让线程数、上游 QPS 与堆内存退化为只受任务数约束。
 * 多会话之间不共享额度（每个 {@code multitask} 调用各有自己的窗口）。</p>
 *
 * <p><b>调度形态</b>：一批任务进一个 FIFO 队列，起「上限」个 worker 自取；每个 worker
 * 跑完一个立刻取下一个，等价于「完成一个补一个」。故没有任何取槽/轮询分派逻辑。</p>
 *
 * <p><b>不设超时</b>：子任务可能运行数天，所有等待路径只有取消检测节拍、没有截止时间，
 * 只会因主流程取消或线程中断提前结束。</p>
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(TaskTalent.class);

    public static final String TOOL_TASK = "task";
    public static final String TOOL_MULTITASK = "multitask";

    /**
     * 超长结果落盘目录（相对 workspace）。
     *
     * <p>{@code .files} 是与宿主共享的 workspace 级文件根（宿主把上传附件落 {@code .files/uploads}），
     * 引擎把超长子任务结果放同一根下（{@code .files/task/<taskId>/result.txt}），可统一忽略与浏览。</p>
     */
    static final String SPILL_DIR = ".files/task";

    /** 默认并发上限的缺省值：配置为 0（未设置）时使用 */
    public static final int DEFAULT_CONCURRENCY = 5;

    /** 默认并发上限的硬上界：默认值是兜底安全网，不是放开并发的入口 */
    public static final int MAX_CONCURRENCY = 10;

    /** 委派深度标记：子代理任务线程内可见，用于拒绝嵌套委派 */
    private static final ThreadLocal<Boolean> TASK_DEPTH = new ThreadLocal<>();

    /** 结果聚合的轮询节拍：只为及时响应取消，不是超时；也是接受新任务的检查间隔 */
    private static final long CANCEL_POLL_MS = 200L;

    /** 单条结果的字符上限：避免单个子代理的输出独占父会话上下文 */
    private static final int MAX_RESULT_CHARS = 20000;

    private final HarnessEngine engine;

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
     * <p>不复用 {@code RunUtil.io()}：worker 会阻塞在子任务的整段 LLM 调用上，占住全局池
     * 线程会拖累宿主其它模块。</p>
     *
     * <p>必须用 {@link SynchronousQueue}：core=0 配无界队列时，ThreadPoolExecutor 只在
     * {@code workerCount < corePoolSize} 时建线程，实际并发恒为 1（且不报错）。这里每次调用
     * 只提交「生效上限」个 worker，线程数不超过该上限；忙完即回收，不留常驻线程。</p>
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
            return DEFAULT_CONCURRENCY;
        }

        if (value > MAX_CONCURRENCY) {
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
        for (AgentDefinition agent : engine.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agent.getName(), agent.getDescription()));
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

        MultiTaskOp taskOp = new MultiTaskOp();
        taskOp.agent_name = taskSpec.agent_name;
        taskOp.description = taskSpec.description;
        taskOp.prompt = taskSpec.prompt;

        MultitaskContext ctx = new MultitaskContext(__parentTrace, __cwd, __sessionId,1, null, null, null);

        TaskPlan plan = preparePlan(taskOp, sessionModelOf(__parentSession));

        // 单任务不排队：上限按调用生效，本次只有 1 个任务，起 worker 反而是多余的一次线程投递
        return runTask(ctx, plan, false, Utils.uuid());
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

        final MultitaskContext ctx = new MultitaskContext(__parentTrace, __cwd, __sessionId, tasks.size(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());

        // 会话模型只解析一次：两次调用之间配置可能被换掉，算出来的上限与取到的实例会不一致
        final String sessionModel = sessionModelOf(__parentSession);

        // 预解析一次并向下传递：执行时不再重走 getModelOrDef，避免「默认模型」的 fallback
        // 在并发下取到与分派时不同的实例
        final List<TaskPlan> plans = preparePlans(tasks, sessionModel);

        // 生效上限按调用算一个数：取会话模型的 concurrency（未配置回退默认值）
        final int cap = resolveConcurrency(concurrencyOf(sessionModel), defaultConcurrency);

        // 定长 + 按下标写：并发完成顺序不定，边算边 add 会让 <index> 与 <task_result> 错位
        final List<CompletableFuture<String>> futures = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            futures.add(new CompletableFuture<String>());
        }

        dispatchQueued(plans, futures, ctx, parentSink, cap);

        return aggregate(futures, ctx);
    }

    // ========== 预解析 ==========

    /** 一个子任务的执行计划：预解析产物，供分派与执行共用 */
    static final class TaskPlan {
        final MultiTaskOp op;
        final ReActAgent.Builder agentBuilder;
        /** 非空表示任务定义本身不可用，直接回该文案 */
        final String invalidReason;

        TaskPlan(MultiTaskOp op, ReActAgent.Builder agentBuilder, String invalidReason) {
            this.op = op;
            this.agentBuilder = agentBuilder;
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
                plans.add(new TaskPlan(empty, null,"ERROR: 第 " + (i + 1) + " 个任务定义为空（null），已跳过。"));
                continue;
            }

            plans.add(preparePlan(op, sessionModel));
        }

        return plans;
    }

    private TaskPlan preparePlan(MultiTaskOp op, String sessionModel) {
        final ReActAgent.Builder agentBuilder;

        try {
            agentBuilder = engine.getAgentBuilderOrNil(op.agent_name, sessionModel);
        } catch (Throwable e) {
            // 名称存在但定义加载失败：同样只废这一条
            return new TaskPlan(op, null, "ERROR: 子代理 '" + op.agent_name + "' 定义加载失败: " + e.getMessage());
        }

        if (agentBuilder == null) {
            // 走 invalidReason 而非裸文本：否则 multitask 里会出现没有外壳、无法对位的一行
            return new TaskPlan(op, null, unknownAgentReason(op));
        }


        return new TaskPlan(op, agentBuilder, null);
    }

    private static String unknownAgentReason(MultiTaskOp op) {
        return "ERROR: 未知的子代理类型 '" + op.agent_name + "'。";
    }

    /** 会话模型的 concurrency（0 = 未设置）；模型不可解析时同样返回 0，走默认值 */
    private int concurrencyOf(String sessionModel) {
        ChatConfig config = engine.getModelOrDef(sessionModel);
        return config == null ? 0 : config.getConcurrency();
    }

    private static String sessionModelOf(AgentSession parentSession) {
        return SessionSelections.modelOf(parentSession);
    }

    private static FluxSink<AgentEvent> streamSinkOf(ReActTrace parentTrace) {
        return (parentTrace != null && parentTrace.getOptions() != null)
                ? parentTrace.getOptions().getStreamSink() : null;
    }

    // ========== 分派 ==========

    /**
     * 队列分派：一批任务进 FIFO 队列，起 {@code cap} 个 worker 自取，跑完一个立刻取下一个。
     *
     * <p>不可解析的任务（子代理未知/不可用）照常入队，但 {@code runTask} 会在入口立即返回
     * 错误结果，不产生任何并发压力。<b>不要为此加过滤</b>：让它们留在队列里，才能使
     * 「下标 → 结果」的对应关系与入参完全一致。</p>
     */
    private void dispatchQueued(List<TaskPlan> plans, List<CompletableFuture<String>> futures,
                                MultitaskContext ctx, FluxSink<AgentEvent> sink, int cap) {
        boolean submitted = SubTaskScheduler.submit(plans.size(), futures, cap, taskExecutor,
                () -> isCancelled(sink),
                i -> runTask(ctx, plans.get(i), true, Utils.uuid()),
                i -> plans.get(i).invalidReason != null
                        ? formatTaskResp(plans.get(i).op, false, plans.get(i).invalidReason, true)
                        : notStarted(plans.get(i).op));

        if (!submitted) {
            LOG.warn("子任务执行器已关闭，{} 个子任务未启动", plans.size());
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
    private String aggregate(List<CompletableFuture<String>> futures, MultitaskContext ctx) {
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

            String line = null;

            while (line == null) {
                if (isCancelled(sink)) {
                    abortedNote = "ERROR: 子任务未完成（主流程已取消，未等待）";
                    break;
                }

                try {
                    String v = f.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
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
        private final int total;
        private final AtomicBoolean rateLimited;
        private final AtomicBoolean timedOut;
        private final AtomicBoolean upstreamUnavailable;
        /** 父代理本次生效的技能 / MCP 视野（子代理必须继承，否则父子行为不一致） */
        private final List<String> skillsSelected;
        private final List<String> mcpsSelected;

        MultitaskContext(ReActTrace parentTrace, String cwd, String sessionId,
                         int total, AtomicBoolean rateLimited, AtomicBoolean timedOut,
                         AtomicBoolean upstreamUnavailable) {
            this.parentTrace = parentTrace;
            this.cwd = cwd;
            this.sessionId = sessionId;
            this.total = total;
            this.rateLimited = rateLimited;
            this.timedOut = timedOut;
            this.upstreamUnavailable = upstreamUnavailable;
            // 只解析一次，多个子任务共享同一份视野快照
            this.skillsSelected = SkillTalent.getSelected(parentTrace.getOptions().getToolContext());
            this.mcpsSelected = McpGatewayTalent.getSelected(parentTrace.getOptions().getToolContext());
        }

        /** 把继承到的视野写进子代理的 toolContext */
        private void applyScope(ModelOptionsAmend<?, ?> options) {
            if (skillsSelected.isEmpty() == false) {
                options.toolContextPut(SkillTalent.CTX_SKILLS_SELECTED, skillsSelected);
            }

            if (mcpsSelected.isEmpty() == false) {
                options.toolContextPut(McpGatewayTalent.CTX_MCPS_SELECTED, mcpsSelected);
            }
        }
    }

    /** 未启动的任务结果（保持总数与下标不变，避免结果错位） */
    private String notStarted(MultiTaskOp task) {
        return formatTaskResp(task, false, notStartedReason(), true);
    }

    /**
     * 「未启动」的原因文案。
     *
     * <p>中断判定读的是<b>当前线程</b>的标志：调用方线程（入队前就已取消 / 执行器已关闭）
     * 能准确反映用户中断；worker 线程上的占位补全会读到 worker 自身状态，可能退化为
     * 「主流程已取消」。这里不做额外的状态传递——真正常见的只有取消，中断那条是兜底。</p>
     */
    private static String notStartedReason() {
        return Thread.currentThread().isInterrupted() ? "执行被中断，任务未启动" : "主流程已取消，任务未启动";
    }

    /**
     * 解析生效的并发上限：模型配置优先，未设置（0）时用默认值。
     *
     * <p>与 {@code ContextCompressionInterceptor#finalContextLength} 同构，不存在
     * 「两级取小」那种「配了 8 却只跑 5」的静默降级。</p>
     *
     * @return 恒为 {@code [1, MAX_CONCURRENCY]}，不存在「不限制」
     */
    static int resolveConcurrency(int modelConcurrency, int defaultConcurrency) {
        if (modelConcurrency > 0) {
            // 模型级配置同样受上界约束：窗口直接决定 worker 线程数，放开等于让一次调用
            // 就能建出上百个平台线程；多会话并发时还会按会话数叠加
            return Math.min(modelConcurrency, MAX_CONCURRENCY);
        }

        // 防御归一：配置侧已保证 ≥ 1，这里只让纯函数自洽
        return defaultConcurrency > 0 ? defaultConcurrency : DEFAULT_CONCURRENCY;
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

        if (plan.agentBuilder == null) {
            // 保底：统一走外壳，不返回裸文本（否则会混进 <multitask_results> 里错位）
            return formatTaskResp(plan.op, false, unknownAgentReason(plan.op), isMultitask);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务开始[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name, ONode.serialize(plan.op));
        }

        try {
            // 标记委派深度：本线程内再触发 task/multitask 会被拒绝
            TASK_DEPTH.set(Boolean.TRUE);

            ReActAgent agent = plan.agentBuilder.build();
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
                            ctx.applyScope(o);

                            for (HarnessExtension extension : engine.getExtensions()) {
                                if (extension.isEnabled()) {
                                    extension.onRequestAssemble(engine, true, agent.name(), o);
                                }
                            }
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
                            ctx.applyScope(o);

                            for (HarnessExtension extension : engine.getExtensions()) {
                                if (extension.isEnabled()) {
                                    extension.onRequestAssemble(engine, true, agent.name(), o);
                                }
                            }
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
        return formatTaskResp(task, Utils.uuid(), successful, result, isMultitask);
    }

    String formatTaskResp(MultiTaskOp task, String taskId, boolean successful, String result, boolean isMultitask) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_result>");
        if (isMultitask) {
            buf.append("<index>").append(task.index).append("</index>");
        }
        buf.append("<description>").append(escapeXml(task.description)).append("</description>");
        buf.append("<agent_name>").append(escapeXml(task.agent_name)).append("</agent_name>");
        buf.append("<result_status>").append(successful ? "success" : "failure").append("</result_status>");
        buf.append("<result_content><![CDATA[").append(toCdata(task, taskId, result)).append("]]></result_content>");
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
     *
     * <p>超限内容会先尝试落盘（见 {@link #trySpill}），落盘成功后头部保留截断说明并给出
     * workspace 相对路径，供宿主的文件读取工具分页读回（工具无关——harness 不假设宿主
     * 一定有 read 工具，也无专属召回工具）；落盘不可用（无 engine / 写入失败）时保持纯截断，
     * 行为与历史版本一致。</p>
     */
    private String toCdata(MultiTaskOp op, String taskId, String text) {
        if (text == null) {
            return "";
        }

        if (text.length() <= MAX_RESULT_CHARS) {
            return text.replace("]]>", "]]]]><![CDATA[>");
        }

        int cut = MAX_RESULT_CHARS;
        // 不要把代理对切成单个 char（会输出乱码）
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }

        String head = text.substring(0, cut);
        String body;

        String spillRef = trySpill(op, taskId, text);
        if (spillRef != null) {
            body = head + "\n...[内容过长已截断：原始长度 " + text.length() + " 字符，此处保留前 " + cut
                    + " 字符；完整结果已落盘到 " + spillRef + "，可按需读取文件]";
            LOG.info("子任务结果超限已落盘: task_id={}, agent={}, 原始长度 {} 字符",
                    taskId, op.agent_name, text.length());
        } else {
            body = head + "\n...[内容过长已截断，原始长度 " + text.length() + " 字符]";
        }

        // CDATA 里只有终止符 ]]> 需要拆开，其余字符（含 < &）无需转义
        return body.replace("]]>", "]]]]><![CDATA[>");
    }

    /**
     * 超长结果落盘。返回 workspace 相对展示路径（如 {@code .files/task/<taskId>/result.txt}），
     * 不可用时返回 null（降级为纯截断）。
     *
     * <p>落盘目录相对 <b>workspace</b> 解析（不在 harnessHome 内）——{@code .files} 是与宿主
     * 共享的约定根，宿主上传附件也落在其下（{@code .files/uploads}）；直接 {@code Paths.get}
     * 相对 JVM CWD 会写错位置，与 {@code TodoTalent#getWorkPath} 的解析口径一致。</p>
     */
    private String trySpill(MultiTaskOp op, String taskId, String text) {
        try {
            if (engine == null || Assert.isEmpty(taskId)) {
                return null;
            }

            Path file = spillFile(taskId);
            if (file == null) {
                return null;
            }

            Files.createDirectories(file.getParent());
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
            return SPILL_DIR + "/" + safeSegment(taskId) + "/result.txt";
        } catch (Throwable e) {
            LOG.warn("子任务结果落盘失败，降级为纯截断: task_id={}, 原因: {}", taskId, e.getMessage());
            return null;
        }
    }

    /** 落盘文件路径；workspace 不可用或 taskId 非法时返回 null */
    private Path spillFile(String taskId) {
        if (engine == null || Assert.isEmpty(engine.getWorkspace())) {
            return null;
        }

        // 与 getWorkPath 同口径：以 workspace 为根（.files 是与宿主共享的约定根，不在 harnessHome 内）
        return Paths.get(engine.getWorkspace(), SPILL_DIR, safeSegment(taskId))
                .toAbsolutePath()
                .normalize()
                .resolve("result.txt");
    }

    /** taskId 可能含路径分隔符或上跳（..）：只保留文件名安全字符，避免路径穿越 */
    private static String safeSegment(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9_.-]", "_");
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
