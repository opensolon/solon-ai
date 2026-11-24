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
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 子任务信息在子会话 context 里的键（随快照落盘，重启后仍可校验归属）。
     * 值为 Map：parentSessionId / agentName / traceKey / description / status / failureKind
     * （status 取 {@link #STATUS_RUNNING} 等，failureKind 只在失败时有；二者只供宿主展示，如回放时的成败图标，引擎不据此拦截续跑）。
     * 宿主读子 trace 请用 {@link #subtaskTraceOf(AgentSession)}，不要自己拼键。
     *
     * @since 4.2
     */
    public static final String META_SUBTASK = "__subtask";

    /**
     * task / multitask 工具结果（ToolMessage metadata）上的子任务索引键，供宿主按 taskId 找回子会话（如过程回放）。
     * 值为 List&lt;Map&gt;：taskId / index / agentName / description。不发给模型。
     *
     * @since 4.2
     */
    public static final String META_SUBTASKS = "_subtasks";

    /** 任务 ID（即子会话 ID）= 前缀 + {@code Utils.guid()}；判断请用 {@link #isTaskId(String)}，不要只比对前缀 */
    private static final String TASK_ID_PREFIX = "task-";

    /**
     * 是否子代理任务 ID（即子会话 ID）：{@code task-} 加 32 位小写十六进制，与生成和续跑校验同一口径。
     * 子会话与主会话共用引擎的 sessionProvider，宿主据此分流（如不常驻内存）、清理过期子会话或拒绝外部访问，
     * 这是判断子会话的唯一标准。
     *
     * @since 4.2
     */
    public static boolean isTaskId(String id) {
        return id != null && id.startsWith(TASK_ID_PREFIX);
    }

    /** {@link #META_SUBTASK} 里 status 的取值（running 在进程崩溃或父流取消后会一直留着，宿主按「已中断」展示） */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";

    /** 父流被取消 / 中断时已启动子任务的 failure_kind（其余取模型服务错误分类或 max_turns，见 {@link #failureKindOf}） */
    private static final String FAILURE_CANCELLED = "cancelled";
    private static final String FAILURE_INTERRUPTED = "interrupted";

    private final HarnessEngine engine;

    /** 正在执行的子任务（进程内运行锁：同一任务不能并发续跑） */
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();

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
    /** 执行器是否归本对象所有（宿主注入的共享执行器不由引擎关闭） */
    private final boolean ownsExecutor;

    /** 引擎私有执行器的线程上限（防止多会话并发 multitask 无限建线程；超出时少起 worker，由已起的 worker 消化） */
    public static final int DEFAULT_MAX_THREADS = 64;

    public TaskTalent(HarnessEngine engine) {
        this(engine, null);
    }

    /**
     * @param sharedExecutor 宿主注入的共享执行器（如引擎池共用一个有界池）；null = 引擎私有、上限 {@link #DEFAULT_MAX_THREADS}
     * @since 4.2.0
     */
    public TaskTalent(HarnessEngine engine, ExecutorService sharedExecutor) {
        this.engine = engine;
        if (sharedExecutor != null) {
            this.taskExecutor = sharedExecutor;
            this.ownsExecutor = false;
        } else {
            this.taskExecutor = new ThreadPoolExecutor(
                    0, DEFAULT_MAX_THREADS,
                    60L, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new TaskThreadFactory());
            this.ownsExecutor = true;
        }
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
        if (ownsExecutor) {
            taskExecutor.shutdown();
        }
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
    public String description(Prompt prompt) {
        return HarnessI18n.get(AiI18n.localeOf(prompt), HarnessI18nKeys.TASK_DESCRIPTION);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Locale locale = AiI18n.localeOf(prompt);
        StringBuilder sb = new StringBuilder();

        sb.append(HarnessI18n.get(locale, HarnessI18nKeys.TASK_INSTRUCTION_AGENTS_TITLE)).append("\n");
        sb.append("<available_agents>\n");
        for (AgentDefinition agent : engine.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agent.getName(), engine.getAgentDescription(agent, locale)));
        }
        sb.append("</available_agents>\n\n");

        appendSessionAgents(prompt, locale, sb);

        sb.append(HarnessI18n.get(locale, HarnessI18nKeys.TASK_INSTRUCTION_STRATEGY)).append("\n");

        return sb.toString();
    }

    /**
     * 补充列出本会话的会话级子代理（{@code generate(saveToFile=false)} 的产物）。
     *
     * <p>按 prompt 携带的会话 ID 现查会话：清单只对创建它的会话可见，其它会话的请求查不到，自然不列；
     * 与委派时的解析（{@link HarnessEngine#resolveAgent}）同一来源，不再经请求上下文转递。</p>
     */
    private void appendSessionAgents(Prompt prompt, Locale locale, StringBuilder sb) {
        if (prompt == null) {
            return;
        }

        Object sessionId = prompt.attrs().get(ChatSession.ATTR_SESSIONID);
        if (!(sessionId instanceof String)) {
            return;
        }

        // 只取已存在的会话：列清单不能顺带把会话建出来
        AgentSession session = engine.getSessionOrNull((String) sessionId);
        Collection<String> names = SessionAgents.names(session);
        if (names.isEmpty()) {
            return;
        }

        sb.append(HarnessI18n.get(locale, HarnessI18nKeys.TASK_INSTRUCTION_SESSION_AGENTS_TITLE)).append("\n");
        sb.append("<session_agents>\n");
        for (String name : names) {
            AgentDefinition agent = SessionAgents.get(session, name);
            if (agent != null) {
                sb.append(String.format("  - \"%s\": %s\n", agent.getName(), agent.getDescription()));
            }
        }
        sb.append("</session_agents>\n\n");
    }

    /**
     * 委派单一任务
     *
     * <p>嵌套调用、找不到代理、定义加载失败、子代理执行异常、子代理因模型服务错误或回合用尽而结束，
     * 返回 {@link ToolResult#error(String)}；其余正常结束返回成功（不解读它返回的内容）。失败项带 {@code ERROR: } 与 result_status。
     * 子代理跑起来过的结果（成败都算）带 {@code <task_id>}，失败另带 {@code <failure_kind>}；模型传 {@code task_id}
     * 即在原子会话上接着做（同 opencode 的 {@code task_id}/{@code sessionID}：失败时从断点重试，完成后可追问）。</p>
     *
     * @param __locale 请求语言（由工具上下文注入；null 为默认语言）
     * @since 4.2
     */
    @ToolMapping(name = TOOL_TASK, description =
            "委派单一任务给专项子代理。适用于需要深度思考、多步操作或特定领域知识（如文件操作、代码分析）的场景。不支持并行调用（并行请用 multitask）。")
    public ToolResult task(@Body SingleTaskOp taskSpec, String __cwd, String __sessionId, String __locale) {
        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return err(HarnessI18n.get(AiI18n.toLocale(__locale), HarnessI18nKeys.TASK_ERROR_NESTED_TASK));
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        MultiTaskOp taskOp = new MultiTaskOp();
        taskOp.agent_name = taskSpec.agent_name;
        taskOp.description = taskSpec.description;
        taskOp.prompt = taskSpec.prompt;
        taskOp.task_id = taskSpec.task_id;

        MultitaskContext ctx = new MultitaskContext(__parentTrace, __cwd, __sessionId,1, null, null, null);

        TaskPlan plan = preparePlan(__parentSession, taskOp, sessionModelOf(__parentSession), ctx.locale);

        // 单任务不排队：上限按调用生效，本次只有 1 个任务，起 worker 反而是多余的一次线程投递
        String text = runTask(ctx, plan, false, plan.taskId);
        ToolResult result = ctx.succeeded.get() > 0 ? ToolResult.success(text) : ToolResult.error(text);
        return withSubtasks(result, Collections.singletonList(plan));
    }

    /**
     * 并行执行多个子任务
     *
     * <p>成败标记：嵌套调用、被取消或中断、所有子任务都失败，返回 {@link ToolResult#error(String)}；
     * 部分失败算成功（每项成败已写在文本的 result_status 里）；空列表（{@code WARNING:}）算成功。文本不变。</p>
     *
     * @param __locale 请求语言（由工具上下文注入；null 为默认语言）
     * @since 4.2
     */
    @ToolMapping(name = TOOL_MULTITASK, description =
            "并行执行多个互不依赖的子任务。要求任务之间必须没有资源竞争（例如：不同的模块开发、多路搜索）。")
    public ToolResult multitask(@Param(name = "tasks", description = "任务列表") List<MultiTaskOp> tasks, String __cwd, String __sessionId, String __locale) {
        final Locale locale = AiI18n.toLocale(__locale);

        if (Assert.isEmpty(tasks)) {
            return ToolResult.success("WARNING: " + HarnessI18n.get(locale, HarnessI18nKeys.TASK_ERROR_EMPTY_TASKS));
        }

        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return err(HarnessI18n.get(locale, HarnessI18nKeys.TASK_ERROR_NESTED_MULTITASK));
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());
        final FluxSink<AgentEvent> parentSink = streamSinkOf(__parentTrace);

        final MultitaskContext ctx = new MultitaskContext(__parentTrace, __cwd, __sessionId, tasks.size(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());

        // 会话模型只解析一次：两次调用之间配置可能被换掉，算出来的上限与取到的实例会不一致
        final String sessionModel = sessionModelOf(__parentSession);

        // 预解析一次并向下传递：执行时不再重走 getModelOrDef，避免「默认模型」的 fallback
        // 在并发下取到与分派时不同的实例
        final List<TaskPlan> plans = preparePlans(__parentSession, tasks, sessionModel, ctx.locale);

        // 生效上限按调用算一个数：取会话模型的 concurrency（未配置回退默认值）
        final int cap = resolveConcurrency(concurrencyOf(sessionModel), defaultConcurrency);

        // 定长 + 按下标写：并发完成顺序不定，边算边 add 会让 <index> 与 <task_result> 错位
        final List<CompletableFuture<String>> futures = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            futures.add(new CompletableFuture<String>());
        }

        dispatchQueued(plans, futures, ctx, parentSink, cap);

        return withSubtasks(aggregate(futures, plans, ctx), plans);
    }

    /** 结果上挂子任务索引（只列本次确实跑起来的任务：被拒的续跑不列，否则前端会按索引隐藏掉那条拒绝信息） */
    private static ToolResult withSubtasks(ToolResult result, List<TaskPlan> plans) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TaskPlan plan : plans) {
            if (plan.started == false) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", plan.taskId);
            item.put("index", plan.op.index);
            item.put("agentName", plan.op.agent_name);
            item.put("description", plan.op.description);
            list.add(item);
        }
        if (list.isEmpty() == false) {
            result.metas().put(META_SUBTASKS, list);
        }
        return result;
    }

    // ========== 预解析 ==========

    /** 一个子任务的执行计划：预解析产物，供分派与执行共用 */
    static final class TaskPlan {
        final MultiTaskOp op;
        final ReActAgent.Builder agentBuilder;
        /** 非空表示任务定义本身不可用，直接回该文案 */
        final String invalidReason;
        /** 子代理自己声明的技能 / MCP（null = 未声明，继承父代理的选择；见 {@link AgentSelections}） */
        final List<String> ownSkills;
        final List<String> ownMcps;
        /** 任务 ID：贯穿流事件、子会话 ID、落盘目录与结果里的 {@code <task_id>}；续跑时沿用原 ID */
        final String taskId;
        /** 非空表示续跑：已取回的子会话 */
        final AgentSession resumeSession;
        /** 子代理是否已在子会话里跑起来（worker 线程写、调用线程读） */
        volatile boolean started;

        TaskPlan(MultiTaskOp op, ReActAgent.Builder agentBuilder, String invalidReason) {
            this(op, agentBuilder, invalidReason, null, null, null);
        }

        TaskPlan(MultiTaskOp op, ReActAgent.Builder agentBuilder, String invalidReason,
                 @Nullable List<String> ownSkills, @Nullable List<String> ownMcps,
                 @Nullable AgentSession resumeSession) {
            this.op = op;
            this.agentBuilder = agentBuilder;
            this.invalidReason = invalidReason;
            this.ownSkills = ownSkills;
            this.ownMcps = ownMcps;
            this.resumeSession = resumeSession;
            this.taskId = resumeSession != null ? resumeSession.getSessionId() : TASK_ID_PREFIX + Utils.guid();
        }
    }

    private List<TaskPlan> preparePlans(AgentSession session, List<MultiTaskOp> tasks, String sessionModel, Locale locale) {
        final List<TaskPlan> plans = new ArrayList<>(tasks.size());
        final Set<String> resumeIds = new HashSet<>();

        for (int i = 0; i < tasks.size(); i++) {
            MultiTaskOp op = tasks.get(i);

            if (op == null) {
                // JSON 反序列化可能带 null 元素：不能 NPE，也不能少一条导致下标错位
                MultiTaskOp empty = new MultiTaskOp();
                empty.index = i + 1;
                empty.agent_name = "";
                empty.description = "";
                empty.prompt = "";
                plans.add(new TaskPlan(empty, null, "ERROR: " + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_ERROR_NULL_TASK, "index", i + 1)));
                continue;
            }

            if (Assert.isNotEmpty(op.task_id) && resumeIds.add(op.task_id) == false) {
                // 同一次调用里重复续跑同一任务：只认第一条
                plans.add(new TaskPlan(op, null, "ERROR: " + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_RESUME_DUPLICATE, "task_id", op.task_id)));
                continue;
            }

            plans.add(preparePlan(session, op, sessionModel, locale));
        }

        return plans;
    }

    private TaskPlan preparePlan(AgentSession session, MultiTaskOp op, String sessionModel, Locale locale) {
        final AgentDefinition definition;
        final ReActAgent.Builder agentBuilder;
        final List<String> ownSkills;
        final List<String> ownMcps;
        AgentSession resumeSession = null;

        if (Assert.isNotEmpty(op.task_id)) {
            resumeSession = openForResume(session, op);
            if (resumeSession == null) {
                return new TaskPlan(op, null, "ERROR: " + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_RESUME_NOT_FOUND, "task_id", op.task_id));
            }
        }

        try {
            // 会话感知：本会话的会话级子代理（generate saveToFile=false）优先，其次引擎级。
            // 先取定义再构建（与 getAgentBuilderOrNull 等价）：子代理自己声明的技能 / MCP 也从这份定义取。
            // 内置子代理的提示词在推理时按本轮语言取，子请求继承父代理的语言（见 applyScope）
            definition = engine.resolveAgent(session, op.agent_name);
            agentBuilder = definition == null ? null : engine.getAgentBuilder(definition, sessionModel);
            ownSkills = AgentSelections.skillsOf(engine, definition);
            ownMcps = AgentSelections.mcpsOf(engine, definition);
        } catch (Throwable e) {
            // 名称存在但定义加载失败：同样只废这一条
            return new TaskPlan(op, null, "ERROR: " + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_ERROR_LOAD_FAILED,
                    "name", op.agent_name, "error", e.getMessage()));
        }

        if (agentBuilder == null) {
            // 走 invalidReason 而非裸文本：否则 multitask 里会出现没有外壳、无法对位的一行
            return new TaskPlan(op, null, unknownAgentReason(op, locale));
        }

        return new TaskPlan(op, agentBuilder, null, ownSkills, ownMcps, resumeSession);
    }

    /**
     * 取回要续跑的子会话：ID 格式非法、不存在、不属于当前父会话、上次没跑起来（没有可续的进度）一律返回 null
     * （不区分原因，避免探测别的会话；同 opencode 只校验归属，不看上次成败）。
     * 成功时把 {@code op.agent_name} 改为原任务的代理（续跑以原任务为准）。
     */
    private AgentSession openForResume(AgentSession parentSession, MultiTaskOp op) {
        if (isTaskId(op.task_id) == false) {
            return null;
        }

        final AgentSession sub;
        try {
            sub = engine.getSession(op.task_id, false);
        } catch (Throwable e) {
            LOG.warn("子任务会话读取失败: task_id={}, 原因: {}", op.task_id, e.getMessage());
            return null;
        }

        if (sub == null) {
            return null;
        }

        Map<String, Object> meta = subtaskMeta(sub);
        if (parentSession == null || parentSession.getSessionId().equals(meta.get("parentSessionId")) == false) {
            return null;
        }

        ReActTrace prior = subtaskTraceOf(sub);
        if (prior == null || Prompt.isEmpty(prior.getOriginalPrompt())) {
            return null;
        }

        Object agentName = meta.get("agentName");
        if (agentName instanceof String) {
            if (op.agent_name != null && op.agent_name.equals(agentName) == false) {
                LOG.warn("续跑子任务时 agent_name 与原任务不一致，以原任务为准: task_id={}, {} -> {}",
                        op.task_id, op.agent_name, agentName);
            }
            op.agent_name = (String) agentName;
        }
        if (Assert.isEmpty(op.description) && meta.get("description") instanceof String) {
            op.description = (String) meta.get("description");
        }

        return sub;
    }

    /** 子任务信息（没有时返回空表，只读场景不写回） */
    @SuppressWarnings("unchecked")
    static Map<String, Object> subtaskMeta(AgentSession sub) {
        Object v = sub.getContext().get(META_SUBTASK);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    /**
     * 取子会话里子代理的 trace（宿主回放子过程用）；没有子任务信息或 trace 时返回 null。
     *
     * @since 4.2
     */
    public static @Nullable ReActTrace subtaskTraceOf(AgentSession sub) {
        Object key = subtaskMeta(sub).get("traceKey");
        if ((key instanceof String) == false) {
            return null;
        }
        Object trace = sub.getContext().get((String) key);
        return trace instanceof ReActTrace ? (ReActTrace) trace : null;
    }

    private static String unknownAgentReason(MultiTaskOp op, Locale locale) {
        return "ERROR: " + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_ERROR_UNKNOWN_AGENT, "name", op.agent_name);
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
                i -> runTask(ctx, plans.get(i), true, plans.get(i).taskId),
                i -> plans.get(i).invalidReason != null
                        ? formatTaskResp(plans.get(i).op, false, plans.get(i).invalidReason, true)
                        : notStarted(plans.get(i).op, ctx.locale));

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
     *
     * <p>放弃等待后，当前及剩余任务按「已完成 → 已启动 → 未启动」取结果（见 {@link #abortedLine}），
     * 不让已跑完的结果和已启动任务的 {@code <task_id>} 随取消一起丢掉。</p>
     *
     * <p>成败标记：被取消或中断，或一个子任务都没成功，标记失败；部分失败算成功。</p>
     *
     * @param plans 与 futures 按下标对应（null 表示不知道哪些已启动，只用于测试）
     */
    private ToolResult aggregate(List<CompletableFuture<String>> futures, @Nullable List<TaskPlan> plans, MultitaskContext ctx) {
        StringBuilder compositeResult = new StringBuilder();
        compositeResult.append("<multitask_results>\n");

        final FluxSink<AgentEvent> sink = streamSinkOf(ctx.parentTrace);

        // 非 null 表示已放弃等待：当前及剩余任务改走 abortedLine
        String abortedNote = null;
        String abortedKind = null;

        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<String> f = futures.get(i);

            if (abortedNote != null) {
                compositeResult.append(abortedLine(f, plans == null ? null : plans.get(i), abortedNote, abortedKind, ctx)).append("\n");
                continue;
            }

            String line = null;

            while (line == null) {
                if (isCancelled(sink)) {
                    abortedNote = "ERROR: " + HarnessI18n.get(ctx.locale, HarnessI18nKeys.TASK_AGGREGATE_CANCELLED);
                    abortedKind = FAILURE_CANCELLED;
                    break;
                }

                try {
                    String v = f.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
                    line = (v == null) ? "" : v;
                } catch (TimeoutException e) {
                    // 轮询节拍：任务还在跑，下一轮重新检查取消
                } catch (ExecutionException e) {
                    line = "ERROR: " + HarnessI18n.fmt(ctx.locale, HarnessI18nKeys.TASK_AGGREGATE_FAILED, "error", rootMessage(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    abortedNote = "ERROR: " + HarnessI18n.get(ctx.locale, HarnessI18nKeys.TASK_AGGREGATE_INTERRUPTED);
                    abortedKind = FAILURE_INTERRUPTED;
                    // 必须跳出：中断标志已置回，下一轮 get 会立即再抛，不退出就是零间隔死循环
                    break;
                }
            }

            compositeResult.append(abortedNote != null ? abortedLine(f, plans == null ? null : plans.get(i), abortedNote, abortedKind, ctx) : line).append("\n");
        }

        compositeResult.append("</multitask_results>");

        if (ctx.rateLimited.get() || ctx.upstreamUnavailable.get() || ctx.timedOut.get()) {
            compositeResult.append("\n<multitask_advice>\n");
            if (ctx.rateLimited.get()) {
                compositeResult.append(HarnessI18n.get(ctx.locale, HarnessI18nKeys.TASK_ADVICE_RATE_LIMIT)).append("\n");
            } else if (ctx.upstreamUnavailable.get()) {
                compositeResult.append(HarnessI18n.get(ctx.locale, HarnessI18nKeys.TASK_ADVICE_UPSTREAM)).append("\n");
            } else {
                compositeResult.append(HarnessI18n.get(ctx.locale, HarnessI18nKeys.TASK_ADVICE_TIMEOUT)).append("\n");
            }
            compositeResult.append("</multitask_advice>");
        }

        // 被取消/中断，或一个都没成功：整次调用没完成要做的事
        boolean failed = abortedNote != null || ctx.succeeded.get() == 0;
        return failed ? ToolResult.error(compositeResult.toString()) : ToolResult.success(compositeResult.toString());
    }

    /**
     * 放弃等待后一条任务的结果：
     * <ul>
     *   <li>已完成 → 用真实结果（包括取消后刚退出、由 runTask 自己带上 task_id 的失败结果）；</li>
     *   <li>已启动但未结束（如卡在长工具调用里）→ 带 task_id 的失败结果，进度在子会话里，可接着做；</li>
     *   <li>未启动 → 原来的放弃说明。</li>
     * </ul>
     */
    private String abortedLine(CompletableFuture<String> f, @Nullable TaskPlan plan, String abortedNote, String abortedKind,
                               MultitaskContext ctx) {
        if (f.isDone() && f.isCompletedExceptionally() == false) {
            String v = f.getNow(null);
            return v == null ? "" : v;
        }

        if (plan != null && plan.started) {
            return startedResp(ctx, plan.op, plan.taskId, false, abortedNote, abortedKind, true);
        }

        return abortedNote;
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
        /** 父代理本次生效的技能 / MCP 视野（子代理没有声明自己的技能 / MCP 时继承，否则父子行为不一致） */
        private final List<String> skillsSelected;
        private final List<String> mcpsSelected;
        /** 父请求的作用域（强制边界与租户模型，子代理必须继承，否则可经委派绕过） */
        private final InputScope scope;
        /** 父代理的语言：子代理继承它，结果文案也按它输出 */
        private final Locale locale;
        /**
         * 成功的子任务数（决定工具的失败标记）。只在 runTask 唯一的成功出口计数：
         * 失败出口有多处（未启动、定义无效、找不到代理、执行异常），以后新增失败分支也不用管计数。
         */
        private final AtomicInteger succeeded = new AtomicInteger();

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
            this.scope = InputScope.of(parentTrace);
            this.locale = parentTrace.getOptions().getLocale();
        }

        /**
         * 把视野与语言写进子代理的选项：子代理自己声明了技能 / MCP 就用自己的，没有才继承父代理的
         * （不取交集：交集可能为空，MCP 视野为空就一个服务都用不了）；作用域（强制边界）照旧继承，最后写入。
         *
         * @param ownSkills     子代理声明的技能（null = 未声明）
         * @param ownMcps       子代理声明的 MCP 服务（null = 未声明）
         */
        private void applyScope(ReActOptionsAmend options, @Nullable List<String> ownSkills, @Nullable List<String> ownMcps) {
            options.locale(locale);

            final List<String> skills = ownSkills != null ? ownSkills : skillsSelected;
            if (skills.isEmpty() == false) {
                options.toolContextPut(SkillTalent.CTX_SKILLS_SELECTED, skills);
            }

            final List<String> mcps = ownMcps != null ? ownMcps : mcpsSelected;
            if (mcps.isEmpty() == false) {
                options.toolContextPut(McpGatewayTalent.CTX_MCPS_SELECTED, mcps);
            }

            if (scope != null) {
                scope.applyTo(options);
            }
        }
    }

    /** 未启动的任务结果（保持总数与下标不变，避免结果错位） */
    private String notStarted(MultiTaskOp task, Locale locale) {
        return formatTaskResp(task, false, notStartedReason(locale), true);
    }

    /**
     * 「未启动」的原因文案。
     *
     * <p>中断判定读的是<b>当前线程</b>的标志：调用方线程（入队前就已取消 / 执行器已关闭）
     * 能准确反映用户中断；worker 线程上的占位补全会读到 worker 自身状态，可能退化为
     * 「主流程已取消」。这里不做额外的状态传递——真正常见的只有取消，中断那条是兜底。</p>
     */
    private static String notStartedReason(Locale locale) {
        if (Thread.currentThread().isInterrupted()) {
            return HarnessI18n.get(locale, HarnessI18nKeys.TASK_NOT_STARTED_INTERRUPTED);
        } else {
            return HarnessI18n.get(locale, HarnessI18nKeys.TASK_NOT_STARTED_CANCELLED);
        }
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

    /**
     * 失败结果：文本带 {@code ERROR: } 前缀给模型看（模型只能从文本判断成败），
     * 失败标记给宿主看（前端、统计不必解析文案）。两者在此一处生成，避免一处有、一处漏。
     */
    private static ToolResult err(String body) {
        return ToolResult.error("ERROR: " + body);
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
            return formatTaskResp(plan.op, false, unknownAgentReason(plan.op, ctx.locale), isMultitask);
        }

        // 进程内运行锁：新任务的 ID 是新生成的，只有续跑会撞上
        if (runningTasks.add(taskId) == false) {
            return formatTaskResp(plan.op, false,
                    "ERROR: " + HarnessI18n.fmt(ctx.locale, HarnessI18nKeys.TASK_RESUME_BUSY, "task_id", taskId), isMultitask);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务开始[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name, ONode.serialize(plan.op));
        }

        final boolean resume = plan.resumeSession != null;
        AgentSession sub = null;
        String traceKey = null;

        try {
            // 标记委派深度：本线程内再触发 task/multitask 会被拒绝
            TASK_DEPTH.set(Boolean.TRUE);

            final ReActAgent agent = plan.agentBuilder.build();
            traceKey = agent.getConfig().getTraceKey();
            final AgentSession session = resume ? plan.resumeSession : engine.getSession(taskId, true);
            sub = session;

            final ReActTrace prior = session.getContext().getAs(traceKey);
            final ReActRequest request;
            final int maxTurns;

            if (resume) {
                // 有可续的进度已在 openForResume 校验过
                prepareResume(prior, session, plan.op.prompt);
                // 回合计数不随续跑归零：在已用回合数上再给一份完整预算。
                // autoRethink 下同样要设：否则上限回到初始值，续跑第一回合就越过反思阈值、被插入一条反思提示
                maxTurns = prior.getTurnCount() + agent.getConfig().getDefaultOptions().getMaxTurns();
                request = agent.prompt();
            } else {
                maxTurns = 0;
                Prompt originalPrompt = Prompt.of(plan.op.prompt);
                originalPrompt.attrs().computeIfAbsent(ChatSession.ATTR_SESSIONID, k -> session.getSessionId());
                request = agent.prompt(originalPrompt);
            }

            markRunning(session, ctx.sessionId, plan.op, traceKey);
            plan.started = true;
            // 子 trace 的指标是累计的：续跑只向父 trace 汇总本次增量
            final Metrics baseline = prior == null ? null : prior.getMetrics().snapshot();

            request.session(session).options(o -> {
                o.toolContextPut(HarnessEngine.ATTR_CWD, ctx.cwd);
                ctx.applyScope(o, plan.ownSkills, plan.ownMcps);

                for (HarnessExtension extension : engine.getExtensions()) {
                    if (extension.isEnabled()) {
                        extension.onRequestAssemble(engine, agent.name(), true, o);
                    }
                }

                if (maxTurns > 0) {
                    o.maxTurns(maxTurns);
                }
            });

            final FluxSink<AgentEvent> sink = streamSinkOf(ctx.parentTrace);
            final RunEndEvent agentEnd;

            if (sink == null) {
                // 同步模式
                agentEnd = (RunEndEvent) request.stream()
                        .doOnNext(chunk -> countLlmError(chunk, ctx.rateLimited, ctx.timedOut,
                                ctx.upstreamUnavailable))
                        .blockLast();
            } else {
                // 流式模式
                agentEnd = (RunEndEvent) request.stream()
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

            requireEnd(agentEnd, sink, ctx.locale);

            countLlmErrorType(agentEnd.getLlmErrorType(), ctx.rateLimited, ctx.timedOut,
                    ctx.upstreamUnavailable);

            if (ctx.parentTrace != null) {
                Metrics metrics = agentEnd.getMetrics();
                ctx.parentTrace.getMetrics().addMetrics(baseline == null ? metrics : metrics.deltaSince(baseline));
            }

            // 模型服务失败 / 回合用尽：子代理是「正常结束」的，但任务没完成，要报失败才会被重试
            final String failureKind = failureKindOf(agentEnd);
            if (failureKind != null) {
                LOG.warn("任务失败[{}/{} - {}]: task_id={}, kind={}", plan.op.index, ctx.total, plan.op.agent_name,
                        taskId, failureKind);
                markEnded(session, STATUS_FAILURE, failureKind);
                return startedResp(ctx, plan.op, taskId, false, "ERROR: " + agentEnd.getText(), failureKind, isMultitask);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("任务成功[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name,
                        plan.op.description);
            }

            // 超长结果落盘到本轮会话工作目录：模型的文件工具按 __cwd 解析相对路径
            // 先拼好结果再计数：拼结果时抛异常会走失败分支，不会误计
            String resp = startedResp(ctx, plan.op, taskId, true, agentEnd.getText(), null, isMultitask);
            markEnded(session, STATUS_SUCCESS, null);
            ctx.succeeded.incrementAndGet();
            return resp;
        } catch (Throwable e) {
            LOG.error("任务失败[{}/{} - {}]: {}", plan.op.index, ctx.total, plan.op.agent_name,
                    e.getMessage(), e);
            LlmErrorEvent.Type type = LlmErrorClassifier.classify(e);
            countLlmErrorType(type, ctx.rateLimited, ctx.timedOut, ctx.upstreamUnavailable);

            String reason = "ERROR: " + HarnessI18n.fmt(ctx.locale, HarnessI18nKeys.TASK_ERROR_FAILED, "error", e.getMessage());

            // 子代理已跑起来（有原始提示词）才给 task_id：进度在子会话里
            ReActTrace trace = (sub == null || traceKey == null) ? null : sub.getContext().getAs(traceKey);
            if (trace != null && Prompt.isEmpty(trace.getOriginalPrompt()) == false) {
                // 父流已取消时与 aggregate 放弃等待的那条同一口径（否则同一次取消会因时序报成 unknown）
                String kind = isCancelled(streamSinkOf(ctx.parentTrace)) ? FAILURE_CANCELLED
                        : (type == null ? "error" : type.value());
                markEnded(sub, STATUS_FAILURE, kind);
                return startedResp(ctx, plan.op, taskId, false, reason, kind, isMultitask);
            }

            return formatTaskResp(plan.op, false, reason, isMultitask);
        } finally {
            // 必须清理：池线程会被复用，残留标记会误伤后续任务
            TASK_DEPTH.remove();
            runningTasks.remove(taskId);
        }
    }

    /**
     * 子代理跑起来过的结果：成败都带 {@code <task_id>}（同 opencode，可接着问或从断点重试），失败另带 {@code <failure_kind>}
     *
     * @param failureKind 失败类型（成功为 null）
     */
    private String startedResp(MultitaskContext ctx, MultiTaskOp op, String taskId, boolean successful, String result,
                               @Nullable String failureKind, boolean isMultitask) {
        return renderTaskResp(op, taskId, true, successful, result, isMultitask, ctx.cwd, ctx.locale, failureKind);
    }

    /**
     * 失败类型：模型服务错误取其分类值，回合用尽为 {@code max_turns}；成功返回 null。
     *
     * <p>不看 {@code isAbnormal()}：returnDirect 工具与 Feedback 也会标异常结束，但任务是完成了的。</p>
     *
     * @since 4.2
     */
    public static String failureKindOf(RunEndEvent agentEnd) {
        LlmErrorEvent.Type type = agentEnd.getLlmErrorType();
        if (type != null) {
            return type.value();
        }

        ReActTrace trace = agentEnd.getTrace();
        if (trace != null && trace.getOptions() != null && trace.getTurnCount() > trace.getOptions().getMaxTurns()) {
            return "max_turns";
        }

        return null;
    }

    /**
     * 续跑前修复子 trace：结束态回到推理点（与主代理 /continue 共用 {@link TraceUtil#reopen}），再把 prompt 作为用户消息追加。
     *
     * <p>不撤末尾的回复（与 /continue 不同）：成功时它是要被追问的答案，失败时它是错误说明（限流 / 回合用尽），
     * 留着正好让子代理知道上次为什么停，同 opencode 保留出错的 assistant 消息。</p>
     *
     * <p>上次停在工具执行中途（父流取消、进程崩溃）时不追加：用户消息会插在 tool_calls 与工具结果之间，
     * 上游会拒收；此时从断点接着跑完这一步。</p>
     */
    static void prepareResume(ReActTrace trace, AgentSession sub, @Nullable String prompt) {
        TraceUtil.reopen(trace);

        if (Assert.isNotEmpty(prompt) && ReActAgent.ID_REASON.equals(trace.getRoute())) {
            ChatMessage message = ChatMessage.ofUser(prompt);
            trace.getWorkingMemory().addMessage(message);
            sub.addMessage(message);
        }
    }

    /** 登记子任务信息并落一次快照（进程重启后仍可校验归属；running 留到结束前，崩溃后宿主按「已中断」展示） */
    private static void markRunning(AgentSession sub, String parentSessionId, MultiTaskOp op, String traceKey) {
        Map<String, Object> meta = new LinkedHashMap<>(subtaskMeta(sub));
        meta.put("parentSessionId", parentSessionId);
        meta.put("agentName", op.agent_name);
        meta.put("traceKey", traceKey);
        meta.put("description", op.description);
        meta.put("status", STATUS_RUNNING);
        meta.remove("failureKind");
        sub.getContext().put(META_SUBTASK, meta);
        sub.updateSnapshot();
    }

    private static void markEnded(AgentSession sub, String status, @Nullable String failureKind) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>(subtaskMeta(sub));
            meta.put("status", status);
            if (failureKind != null) {
                meta.put("failureKind", failureKind);
            }
            sub.getContext().put(META_SUBTASK, meta);
            sub.updateSnapshot();
        } catch (Throwable e) {
            LOG.warn("子任务状态保存失败: task_id={}, 原因: {}", sub.getSessionId(), e.getMessage());
        }
    }

    /**
     * 子代理流必须以结束事件收尾：没有时按父流是否已取消给出原因（异常信息会进入任务结果，按父代理语言输出）
     */
    static void requireEnd(RunEndEvent agentEnd, FluxSink<AgentEvent> parentSink, Locale locale) {
        if (agentEnd == null) {
            if (isCancelled(parentSink)) {
                throw new IllegalStateException(HarnessI18n.get(locale, HarnessI18nKeys.TASK_ERROR_PARENT_PAUSED));
            }
            throw new IllegalStateException(HarnessI18n.get(locale, HarnessI18nKeys.TASK_ERROR_NO_RESPONSE));
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
        return formatTaskResp(task, taskId, successful, result, isMultitask, null);
    }

    /**
     * @param cwd 父会话本轮工作目录（超长结果落盘位置；空表示工作区根）
     */
    String formatTaskResp(MultiTaskOp task, String taskId, boolean successful, String result, boolean isMultitask,
                          String cwd) {
        return formatTaskResp(task, taskId, successful, result, isMultitask, cwd, null);
    }

    /**
     * @param locale 截断说明的语言（null 为默认语言）
     */
    String formatTaskResp(MultiTaskOp task, String taskId, boolean successful, String result, boolean isMultitask,
                          String cwd, Locale locale) {
        return renderTaskResp(task, taskId, false, successful, result, isMultitask, cwd, locale, null);
    }

    /**
     * @param showTaskId  是否输出 {@code <task_id>}：只有子代理跑起来过才给（没跑起来的任务没有子会话可接）
     * @param failureKind 失败类型（null = 不输出）
     */
    private String renderTaskResp(MultiTaskOp task, String taskId, boolean showTaskId, boolean successful, String result,
                                  boolean isMultitask, String cwd, Locale locale, @Nullable String failureKind) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_result>");
        if (isMultitask) {
            buf.append("<index>").append(task.index).append("</index>");
        }
        if (showTaskId) {
            buf.append("<task_id>").append(escapeXml(taskId)).append("</task_id>");
        }
        buf.append("<description>").append(escapeXml(task.description)).append("</description>");
        buf.append("<agent_name>").append(escapeXml(task.agent_name)).append("</agent_name>");
        buf.append("<result_status>").append(successful ? "success" : "failure").append("</result_status>");
        if (failureKind != null) {
            buf.append("<failure_kind>").append(escapeXml(failureKind)).append("</failure_kind>");
        }
        buf.append("<result_content><![CDATA[").append(toCdata(task, taskId, result, cwd, locale)).append("]]></result_content>");
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
     * 相对本轮工作目录的路径，供宿主的文件读取工具分页读回（工具无关——harness 不假设宿主
     * 一定有 read 工具，也无专属召回工具）；落盘不可用（无 engine / 写入失败）时保持纯截断，
     * 行为与历史版本一致。</p>
     */
    private String toCdata(MultiTaskOp op, String taskId, String text, String cwd, Locale locale) {
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

        String spillRef = trySpill(op, taskId, text, cwd);
        if (spillRef != null) {
            body = head + "\n" + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_RESULT_SPILLED,
                    "length", text.length(), "kept", cut, "path", spillRef);
            LOG.info("子任务结果超限已落盘: task_id={}, agent={}, 原始长度 {} 字符",
                    taskId, op.agent_name, text.length());
        } else {
            body = head + "\n" + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_RESULT_TRUNCATED, "length", text.length());
        }

        // CDATA 里只有终止符 ]]> 需要拆开，其余字符（含 < &）无需转义
        return body.replace("]]>", "]]]]><![CDATA[>");
    }

    /**
     * 超长结果落盘。返回相对本轮工作目录的展示路径（如 {@code .files/task/<taskId>/result.txt}），
     * 不可用时返回 null（降级为纯截断）。
     *
     * <p>结果要给模型用文件工具分页读取，而文件工具按 {@code __cwd} 解析相对路径且<b>始终读本地文件</b>，
     * 所以必须落在本轮工作目录的本地副本上（规则见 {@link #spillSourceOf(MountSource, String, String)}）。
     * 不写 harnessHome / 状态来源——模型不可见。
     * {@code .files} 是与宿主共享的约定根（宿主附件在 {@code .files/uploads}）。</p>
     */
    private String trySpill(MultiTaskOp op, String taskId, String text, String cwd) {
        try {
            if (engine == null || Assert.isEmpty(taskId)) {
                return null;
            }

            MountSource source = spillSourceOf(engine.getWorkspaceSource(), engine.getWorkspace(), cwd);
            if (source == null) {
                return null;
            }

            String path = SPILL_DIR + "/" + safeSegment(taskId) + "/result.txt";
            source.writeString(path, text);
            return path;
        } catch (Throwable e) {
            LOG.warn("子任务结果落盘失败，降级为纯截断: task_id={}, 原因: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 落盘来源。本轮根 = cwd（缺省取工作区根）：
     * <ul>
     *   <li>工作区来源可本地访问且其本地根就是本轮根 → 用工作区来源；</li>
     *   <li>否则 → 以本轮根为根的本地来源。工作区来源是远程时也一样——写到远端的话，
     *       模型用本地文件工具按给出的路径读不到（读写分裂，见 {@code MountManager(String, MountSource)} 的说明）；</li>
     *   <li>既没有 cwd 也没有工作区根：只有本地工作区来源可用，否则返回 null（降级为纯截断）。</li>
     * </ul>
     */
    static MountSource spillSourceOf(MountSource workspaceSource, String workspace, String cwd) {
        String root = Assert.isNotEmpty(cwd) ? cwd : workspace;
        Path localRoot = (workspaceSource == null) ? null
                : workspaceSource.getLocalRoot().map(p -> p.toAbsolutePath().normalize()).orElse(null);

        if (Assert.isEmpty(root)) {
            return localRoot == null ? null : workspaceSource;
        }

        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        if (rootPath.equals(localRoot)) {
            return workspaceSource;
        }
        return FileMountSource.of(rootPath);
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
        @Param(name = "prompt", description = "发给子代理的详细指令。子代理看不到主会话的历史，必须在此提供任务所需的所有背景信息、具体要求及预期输出格式。")
        public String prompt;
        @Param(name = "description", description = "任务内容的极简摘要（如：'重构用户认证逻辑'）。该描述将作为标签出现在执行日志和结果摘要中，用于快速识别任务意图。")
        public String description;
        /** @since 4.2.0 */
        @Param(name = "task_id", required = false, description = "可选。接着之前的子任务做（取自结果里的 <task_id>，成败均可）：子代理保留之前的全部上下文。失败时从断点重试，prompt 填“继续”或修正说明；完成后可追问或补充要求，prompt 填后续指令。agent_name 以原任务为准。新任务不要填。")
        public String task_id;

        @Override
        public String toString() {
            return "SingleTaskOp{" +
                    "task_id='" + task_id + '\'' +
                    ", agent_name='" + agent_name + '\'' +
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
