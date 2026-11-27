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
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.LlmErrorClassifier;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.ToolCall;
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
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Internal;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
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
 * 子代理（任务）才能：把子代理暴露为 {@code task}（同步）与 {@code task_start} / {@code task_wait} / {@code task_stop}（后台）工具。
 *
 * <p><b>后台任务</b>（参考 bash_start）：{@code task_start} 一次启动一个子代理并立即返回 task_id，主代理自己用
 * {@code task_wait} 取结果、{@code task_stop} 停止，不做推送通知与唤醒。后台任务<b>跟着父代理这一轮走</b>：
 * 这一轮结束（含异常退出、人工审批暂停）时，未结束的一律取消（见 {@link #endRound}），用户停止父会话时一并停下；
 * 进度留在子会话里，之后可带 task_id 接着做。</p>
 *
 * <p><b>并发契约</b>：后台任务按<b>父会话</b>排队（队列在父会话 attrs 里，见 {@link SubTaskQueue}），上限取当前会话所选模型的
 * {@code AiConfig#concurrency}（0 = 未设置）优先，否则取 {@link #DEFAULT_CONCURRENCY}，上界 {@link #MAX_CONCURRENCY}。
 * 恒为有限正整数，<b>不存在「不限制」</b>——那会让线程数、上游 QPS 与堆内存退化为只受任务数约束。
 * 超出上限的排队，跑完一个补一个。同步的 {@code task} 在调用线程里跑，不占队列。</p>
 *
 * <p><b>不设超时</b>：子任务可能运行很久，等待路径只有取消检测节拍；{@code task_wait} 的超时只是「先返回、任务继续跑」。</p>
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(TaskTalent.class);

    public static final String TOOL_TASK = "task";
    /** @since 4.2.0 */
    public static final String TOOL_TASK_START = "task_start";
    /** @since 4.2.0 */
    public static final String TOOL_TASK_WAIT = "task_wait";
    /** @since 4.2.0 */
    public static final String TOOL_TASK_STOP = "task_stop";

    /**
     * 是否本才能的委派工具（task / task_start / task_wait / task_stop）。宿主据此把它们当内部工具
     * （不作行动证据、不单独展示工具卡），不要自己维护工具名清单。
     *
     * @since 4.2.0
     */
    public static boolean isTaskTool(@Nullable String toolName) {
        return TOOL_TASK.equals(toolName) ||
                TOOL_TASK_START.equals(toolName) ||
                TOOL_TASK_WAIT.equals(toolName) ||
                TOOL_TASK_STOP.equals(toolName);
    }

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

    /** task_wait 的轮询节拍：只为及时响应取消，不是超时 */
    private static final long CANCEL_POLL_MS = 200L;

    /**
     * 停止后台任务（task_stop、轮次结束）时，已定为正常结束的任务等它收尾的上限（只剩拼结果、落盘，正常是毫秒级）。
     * 超过就按停止口径报失败，不让停止无限期地等下去。
     */
    private static final long SETTLE_WAIT_MS = 10_000L;

    /** 单条结果的字符上限：避免单个子代理的输出独占父会话上下文 */
    private static final int MAX_RESULT_CHARS = 20000;

    /**
     * 子任务信息在子会话 context 里的键（随快照落盘，重启后仍可校验归属）。
     * 值为 Map：parentSessionId / parentCallId / agentName / traceKey / description / status / failureKind
     * （parentCallId 为最近一次启动它的父调用，取不到时没有）
     * （status 取 {@link #STATUS_RUNNING} 等，failureKind 只在失败时有；二者只供宿主展示，如回放时的成败图标，引擎不据此拦截续跑）。
     * 宿主读子 trace 请用 {@link #subtaskTraceOf(AgentSession)}，不要自己拼键。
     *
     * @since 4.2
     */
    public static final String META_SUBTASK = "__subtask";

    /**
     * task / task_start 工具结果（ToolMessage metadata）上的子任务索引键，供宿主按 taskId 找回子会话（如过程回放）。
     * 值为 List&lt;Map&gt;（一次调用一项）：taskId / agentName / description / status / failureKind。不发给模型。
     * （旧版本写入的数据可能一次多项、多一个 index，宿主读取时按列表处理、忽略不认识的字段。）
     *
     * <p>status / failureKind 是<b>这一次调用</b>的结果（取值同 {@link #META_SUBTASK}，failureKind 只在失败时有），
     * 不随之后的续跑改变；子会话 {@link #META_SUBTASK} 里的只记最近一次。旧数据没有这两项。</p>
     *
     * <p>task_start 返回时子任务还在跑，索引项没有 status；父代理这一轮结束时把本轮后台任务的结果补回各自的
     * task_start 结果上并落快照。子过程按 task_start 的调用 id 切分（与事件的 parentCallId 一致），task_wait 不带本索引。</p>
     *
     * @since 4.2
     */
    public static final String META_SUBTASKS = "_subtasks";

    /**
     * 子会话消息（子 trace 工作记忆里的 ChatMessage metadata）上的调用戳：该消息产生于哪次父 task / task_start 调用，
     * 值为父工具调用 id（同 {@link TaskWrapEvent#getParentCallId()}）。供宿主按调用切分子过程（同一子任务续跑多次时，
     * 每次调用只回放自己那段）。
     *
     * <p>只在续跑开始前打：用上一次的 parentCallId 给上一次的消息补戳。因此<b>最近一次调用</b>的消息没有戳，
     * 它们就是最后一条有戳消息之后的部分，归子会话 {@link #META_SUBTASK} 的 parentCallId。
     * 戳随消息对象走，上下文压缩复制消息时一并保留。取不到调用 id（文本模式、测试直调）时不打。</p>
     *
     * @since 4.2
     */
    public static final String META_PARENT_CALL_ID = "_parentCallId";

    /**
     * 父 trace extras 上「执行中的 task / task_start 调用 → 本次委派的子任务索引」的键，供宿主在工具返回前（运行中刷新、
     * 进程中途退出后重启）按调用找回子会话。
     *
     * <p>值为 Map&lt;callId, List&lt;Map&gt;&gt;：callId 即 {@link ReActTrace#getActingCallId()}（等于 lastReasonMessage 中对应
     * ToolCall 的 uuid、流式工具事件的 callId），列表项同 {@link #META_SUBTASKS}（taskId / agentName / description）。
     * 委派前在调用线程里登记并落一次父快照（连同 lastReasonMessage）；工具返回后不删（下一轮 reset 清空，
     * 登记时顺手去掉不属于当前 lastReasonMessage 的旧条目）。宿主应只对「还没写进工作记忆的那一步」里的调用使用它，
     * 并用子会话 {@link #META_SUBTASK} 的 parentCallId 反向校验（不等即丢弃：还没启动，或已被后来的调用续跑）。</p>
     *
     * @since 4.2
     */
    public static final String EXTRA_RUNNING_SUBTASKS = "_runningSubtasks";

    /** 任务 ID（即子会话 ID）= 前缀 + {@code Utils.guid()}；判断请用 {@link #isTaskId(String)}，不要只比对前缀 */
    private static final String TASK_ID_PREFIX = "task-";

    /**
     * 是否子代理任务 ID（即子会话 ID）：{@code task-} 加 32 位小写十六进制，与生成和续跑校验同一口径。
     * 子会话与主会话共用引擎的 sessionProvider，一律带父会话标识取
     * （{@link AgentSessionProvider#getSession(String, String, boolean)}，宿主可把它存在父会话之下，
     * 删父会话时一并删除）。宿主据此分流（如不常驻内存）、清理过期子会话或拒绝外部访问，
     * 这是判断子会话的唯一标准。
     *
     * @since 4.2
     */
    public static boolean isTaskId(String id) {
        return id != null && TASK_ID_PATTERN.matcher(id).matches();
    }

    private static final Pattern TASK_ID_PATTERN = Pattern.compile(TASK_ID_PREFIX + "[0-9a-f]{32}");

    /** {@link #META_SUBTASK} 里 status 的取值（running 在进程崩溃或父流取消后会一直留着，宿主按「已中断」展示） */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";
    /** 后台任务在等空位（只出现在 task_start / task_wait 返回给模型的文本里） */
    private static final String STATUS_QUEUED = "queued";
    /** 后台任务已结束、结果还没取回（同上） */
    private static final String STATUS_COMPLETED = "completed";

    /** 父流被取消 / 中断时已启动子任务的 failure_kind（其余取模型服务错误分类或 max_turns，见 {@link #failureKindOf}） */
    private static final String FAILURE_CANCELLED = "cancelled";
    private static final String FAILURE_INTERRUPTED = "interrupted";

    /**
     * 子 trace extras：这次运行按停止结束时的失败类型（{@link #FAILURE_CANCELLED} / {@link #FAILURE_INTERRUPTED}）。
     * 结束事件照常转给宿主，宿主经 {@link #failureKindOf} 读到它，实时就显示「被中断」；续跑开始时清掉。
     */
    private static final String EXTRA_STOP_KIND = "_stopKind";

    private final HarnessEngine engine;

    /**
     * 正在执行的子任务 → 父会话标识（本进程内）。
     *
     * <p>一张表、一步原子登记，管三件事：</p>
     * <ul>
     * <li>运行锁：同一任务不能并发续跑（新任务的 ID 是新生成的，只有续跑会撞上）；</li>
     * <li>能否删除：父流被取消后子任务线程可能还在跑（如卡在长工具调用里），此时父会话已不忙，
     *     但删掉它，子任务结束时会把子会话写回来——宿主删除前看 {@code engine.status(session).isRemovable()}；</li>
     * <li>能否回收：{@link HarnessEngine#isQuiescent()} 也看它，引擎池不会回收还有子任务在跑的引擎。</li>
     * </ul>
     *
     * <p>按会话标识登记（不放会话 attrs）：会话实例被移出内存再重建也不丢。</p>
     */
    private final Map<String, String> runningTasks = new ConcurrentHashMap<>();

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
     * {@code workerCount < corePoolSize} 时建线程，实际并发恒为 1（且不报错）。每个父会话的 worker
     * 不超过「生效上限」（见 {@link SubTaskQueue}）；忙完即回收，不留常驻线程。</p>
     */
    private final ExecutorService taskExecutor;
    /** 执行器是否归本对象所有（宿主注入的共享执行器不由引擎关闭） */
    private final boolean ownsExecutor;

    /** 引擎私有执行器的线程上限（防止多会话并发的后台任务无限建线程；建不出线程时少起 worker，由已起的 worker 消化） */
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
     * 指定父会话下正在执行的子任务数（本进程内）。
     *
     * <p>供引擎汇集 {@code engine.status(session)} 时读取。宿主请看会话状态的
     * {@code isRemovable()} / {@code getRunningSubtaskCount()}，不要直接调本方法。</p>
     *
     * @since 4.2.0
     */
    @Internal
    public int getRunningSubtaskCount(@Nullable String parentSessionId) {
        if (parentSessionId == null) {
            return 0;
        }

        // 同时在跑的子任务受并发线程约束，遍历开销可忽略
        int count = 0;
        for (String parent : runningTasks.values()) {
            if (parentSessionId.equals(parent)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 是否有正在执行的子任务（任意父会话，本进程内）。
     *
     * <p>供引擎判定 {@link HarnessEngine#isQuiescent()}，宿主不要直接调本方法。</p>
     *
     * @since 4.2.0
     */
    @Internal
    public boolean hasRunningSubtasks() {
        return runningTasks.isEmpty() == false;
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
            Thread t = new Thread(r, "solon-ai-subtask-" + seq.incrementAndGet());
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

        final AgentSession session = sessionOf(prompt);
        appendSessionAgents(session, locale, sb);

        sb.append(HarnessI18n.get(locale, HarnessI18nKeys.TASK_INSTRUCTION_STRATEGY)).append("\n");

        return sb.toString();
    }

    /** prompt 携带的会话（只取已存在的：列清单不能顺带把会话建出来）；没有返回 null */
    private @Nullable AgentSession sessionOf(Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        Object sessionId = prompt.attrs().get(ChatSession.ATTR_SESSIONID);
        return sessionId instanceof String ? engine.getSessionOrNull((String) sessionId) : null;
    }

    /**
     * 补充列出本会话的会话级子代理（{@code generate(saveToFile=false)} 的产物）。
     *
     * <p>按 prompt 携带的会话 ID 现查会话：清单只对创建它的会话可见，其它会话的请求查不到，自然不列；
     * 与委派时的解析（{@link HarnessEngine#resolveAgent}）同一来源，不再经请求上下文转递。</p>
     */
    private static void appendSessionAgents(@Nullable AgentSession session, Locale locale, StringBuilder sb) {
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
     * 即在原子会话上接着做（失败时从断点重试，完成后可追问）。</p>
     *
     * @param __locale 请求语言（由工具上下文注入；null 为默认语言）
     * @since 4.2
     */
    @ToolMapping(name = TOOL_TASK, description =
            "委派单一任务给专项子代理。适用于需要深度思考、多步操作或特定领域知识（如文件操作、代码分析）的场景。")
    public ToolResult task(@Body SingleTaskOp taskSpec, String __cwd, String __sessionId, String __locale) {
        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return err(HarnessI18n.get(AiI18n.toLocale(__locale), HarnessI18nKeys.TASK_ERROR_NESTED_TASK));
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        // 停止判定的基准：在任何准备动作之前取，准备期间（预解析、登记索引都会落盘）发生的停止也算
        final int stopGen = SessionRuns.cancelCount(__parentSession);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        CallContext ctx = new CallContext(__parentTrace, __cwd, __sessionId, false);
        ctx.watchParent(__parentSession, stopGen);

        TaskPlan plan = preparePlan(__parentSession, taskSpec, sessionModelOf(__parentSession), ctx.locale);
        if (isQueuedOrRunning(__parentSession, plan)) {
            return ToolResult.error(busyResp(plan, ctx.locale));
        }
        registerRunning(ctx, __parentSession, plan);

        // 子代理与父代理同线程，运行句柄却登记在子会话上：在父会话上挂一个联动句柄，父会话被停止（任一方式，
        // 含不推进取消代数的 cancelActive）时一并停下子代理。入口之后已被取消过，登记即失败并就地触发（不再启动）；
        // 线程已带中断标志（入口之后被 cancelActive 打断过）同样算已停止
        final ParentStopLink link = new ParentStopLink(ctx, plan);
        // 用联动登记（不计入活跃流）：子任务不占用父会话
        if (SessionRuns.attachLinked(__parentSession, link, stopGen) && Thread.currentThread().isInterrupted()) {
            link.dispose();
        }

        // 同步任务不排队：在调用线程里直接跑，不占后台队列
        final String text;
        try {
            text = runTask(ctx, plan);
        } finally {
            SessionRuns.detach(__parentSession, link);
        }

        // 子代理退出时清掉了线程的中断标志（runTask 收尾落盘前也会清）：父代理已被停止就还回去，它才会停下。
        // 必须在所有落盘之后：带着中断标志写文件会被拒（ClosedByInterruptException）
        if (ctx.isParentStopped() || ctx.interruptCleared) {
            Thread.currentThread().interrupt();
        }

        ToolResult result = ctx.succeeded.get() > 0 ? ToolResult.success(text) : ToolResult.error(text);
        return withSubtasks(result, plan);
    }

    // ========== 后台任务（task_start / task_wait / task_stop） ==========

    /**
     * 在后台启动一个子任务，立即返回 task_id（参数与 {@link #task} 相同）。
     *
     * <p>嵌套调用、找不到代理、定义加载失败、要续跑的任务仍在执行、父代理已被停止，返回 {@link ToolResult#error(String)}；
     * 启动或进入排队算成功。结果上挂 {@link #META_SUBTASKS}（子过程按本次调用 id 切分），任务结果用 {@link #taskWait} 取。</p>
     *
     * @param __locale 请求语言（由工具上下文注入；null 为默认语言）
     * @since 4.2.0
     */
    @ToolMapping(name = TOOL_TASK_START, description =
            "在后台启动一个任务（交给子代理执行），立即返回 task_id，不等它完成。需要同时做几件互不依赖的事时，逐个调用本工具启动（每次一个任务），再用 task_wait 取回结果。本轮回答结束时，未取回的后台任务会被取消。")
    public ToolResult taskStart(@Body SingleTaskOp taskSpec, String __cwd, String __sessionId, String __locale) {
        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return err(HarnessI18n.get(AiI18n.toLocale(__locale), HarnessI18nKeys.TASK_ERROR_NESTED_TASK));
        }

        final AgentSession parentSession = engine.getSession(__sessionId);
        // 停止判定的基准：在任何准备动作之前取（见 task）
        final int stopGen = SessionRuns.cancelCount(parentSession);
        final ReActTrace parentTrace = ReActTrace.getCurrent(parentSession.getContext());

        final CallContext ctx = new CallContext(parentTrace, __cwd, __sessionId, true);
        ctx.watchParent(parentSession, stopGen);

        // 会话模型只解析一次：上限与取到的实例保持一致
        final String sessionModel = sessionModelOf(parentSession);
        final TaskPlan plan = preparePlan(parentSession, taskSpec, sessionModel, ctx.locale);
        if (plan.invalidReason != null || plan.agentBuilder == null) {
            return ToolResult.error(runTask(ctx, plan));
        }

        // 续跑撞车在入队时就拒绝：不要排了很久才失败
        if (isQueuedOrRunning(parentSession, plan)) {
            return ToolResult.error(busyResp(plan, ctx.locale));
        }
        final SubTaskQueue<AsyncTask> queue = SubTaskQueue.of(parentSession, true);

        registerRunning(ctx, parentSession, plan);

        final AsyncTask task = new AsyncTask(ctx, plan, parentSession);
        // 父会话被停止（任一方式）时一并停下；入口之后已被取消过就不再启动
        if (SessionRuns.attachLinked(parentSession, task.link, stopGen) == false) {
            return ToolResult.error(notStarted(plan.op, ctx.locale));
        }

        final int cap = resolveConcurrency(concurrencyOf(sessionModel), defaultConcurrency);
        final boolean running = queue.submit(task, cap, taskExecutor);

        ToolResult result = ToolResult.success(renderStarted(plan, running ? STATUS_RUNNING : STATUS_QUEUED, ctx.locale));
        result.metas().put(META_SUBTASKS, indexOf(plan));
        return result;
    }

    /**
     * 等后台子任务完成并取回结果。
     *
     * <p>指定 task_id 时等这一个（已取回过的再取一次照样返回）；不指定时等本轮任意一个未取回的任务完成就返回它
     * （同时有几个已完成时按启动顺序取第一个），并附上其余未取回任务的状态。成败标记跟着取回的那个任务（同 {@link #task}）；没有可等的任务算成功（{@code WARNING:}）；
     * 等到超时算成功（返回各任务当前状态，任务继续在后台跑）；等待中父代理被停止算失败。</p>
     *
     * @param timeoutMs 最多等多久（毫秒）；null 或 &lt;= 0 一直等到完成
     * @since 4.2.0
     */
    @ToolMapping(name = TOOL_TASK_WAIT, description =
            "等待 task_start 启动的后台任务完成并取回结果（格式同 task）。不传 task_id 时等本轮任意一个未取回的任务，返回最先完成的那个，并列出其余任务的状态；要拿全部结果就反复调用，直到没有待取回的任务。")
    public ToolResult taskWait(@Param(name = "task_id", required = false, description = "可选。要等的任务（task_start 返回的 task_id）；不传则等最先完成的一个。") String taskId,
                               @Param(name = "timeout_ms", required = false, description = "可选。最多等多久（毫秒）；到时仍未完成就返回各任务的当前状态，任务继续在后台运行。不传或 0 表示一直等到有结果。") Integer timeoutMs,
                               String __sessionId, String __locale) {
        final Locale locale = AiI18n.toLocale(__locale);

        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (TASK_DEPTH.get() != null) {
            return err(HarnessI18n.get(locale, HarnessI18nKeys.TASK_ERROR_NESTED_TASK));
        }

        final AgentSession parentSession = engine.getSession(__sessionId);
        final int stopGen = SessionRuns.cancelCount(parentSession);
        final FluxSink<AgentEvent> sink = streamSinkOf(ReActTrace.getCurrent(parentSession.getContext()));
        final SubTaskQueue<AsyncTask> queue = SubTaskQueue.of(parentSession, false);

        final List<AsyncTask> targets;
        if (Assert.isNotEmpty(taskId)) {
            AsyncTask task = queue == null ? null : queue.get(taskId);
            if (task == null) {
                return err(HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_ASYNC_UNKNOWN, "task_id", taskId));
            }
            targets = Collections.singletonList(task);
        } else {
            targets = queue == null ? Collections.<AsyncTask>emptyList() : queue.uncollected();
            if (targets.isEmpty()) {
                return ToolResult.success("WARNING: " + HarnessI18n.get(locale, HarnessI18nKeys.TASK_WAIT_NONE));
            }
        }

        final long deadline = (timeoutMs != null && timeoutMs > 0) ? System.currentTimeMillis() + timeoutMs : 0L;
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[targets.size()];
        for (int i = 0; i < futures.length; i++) {
            futures[i] = targets.get(i).result;
        }
        final CompletableFuture<Object> any = CompletableFuture.anyOf(futures);

        AsyncTask done;
        while ((done = firstDone(targets)) == null) {
            // 父代理被停止：后台任务已由联动句柄停下，这里只结束等待
            if (isCancelled(sink) || SessionRuns.isCancelledSince(parentSession, stopGen)) {
                return err(HarnessI18n.get(locale, HarnessI18nKeys.TASK_STOPPED_CANCELLED));
            }

            long wait = CANCEL_POLL_MS;
            if (deadline > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return ToolResult.success(HarnessI18n.get(locale, HarnessI18nKeys.TASK_WAIT_TIMEOUT) + "\n"
                            + statusList(queue, targets));
                }
                wait = Math.min(wait, left);
            }

            try {
                any.get(wait, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // 节拍到了或有任务异常结束：回到循环头重新取
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return err(HarnessI18n.get(locale, HarnessI18nKeys.TASK_STOPPED_INTERRUPTED));
            }
        }

        queue.collect(done);
        StringBuilder text = new StringBuilder(resultOf(done));
        List<AsyncTask> rest = queue.uncollected();
        if (rest.isEmpty() == false) {
            text.append("\n").append(statusList(queue, rest));
        }
        return done.succeeded() ? ToolResult.success(text.toString()) : ToolResult.error(text.toString());
    }

    /**
     * 停止后台子任务：排队中的不再启动；运行中的停下（进度在子会话里，可带 task_id 接着做）；已结束的返回它的结果。
     * 停止后视为已取回。找不到任务返回 {@link ToolResult#error(String)}，其余算成功（停止正是要做的事）。
     *
     * @since 4.2.0
     */
    @ToolMapping(name = TOOL_TASK_STOP, description =
            "停止 task_start 启动的后台任务。排队中的不再启动；运行中的会被停下，进度保留，之后可用 task 或 task_start 带上 task_id 接着做。")
    public ToolResult taskStop(@Param(name = "task_id", description = "要停止的任务（task_start 返回的 task_id）。") String taskId,
                               String __sessionId, String __locale) {
        final Locale locale = AiI18n.toLocale(__locale);

        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        final AgentSession parentSession = engine.getSession(__sessionId);
        final SubTaskQueue<AsyncTask> queue = SubTaskQueue.of(parentSession, false);
        final AsyncTask task = queue == null ? null : queue.get(taskId);
        if (task == null) {
            return err(HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_ASYNC_UNKNOWN, "task_id", taskId));
        }

        queue.collect(task);

        if (task.result.isDone() == false) {
            if (queue.unqueue(task)) {
                task.plan.end(STATUS_FAILURE, FAILURE_CANCELLED, true);
            } else {
                String settled = stopRunning(task, FAILURE_CANCELLED);
                if (settled != null) {
                    // 停之前子代理已正常结束：按它的真实结果报
                    return task.succeeded() ? ToolResult.success(settled) : ToolResult.error(settled);
                }
            }
        } else {
            // 停之前已经结束：直接给结果
            String text = resultOf(task);
            return task.succeeded() ? ToolResult.success(text) : ToolResult.error(text);
        }

        // 跑起来过才给 task_id（没跑起来的没有子会话可接；续跑的本来就有）
        final boolean started = task.plan.startedSession != null || task.plan.resumeSession != null;
        return ToolResult.success(renderTaskResp(task.plan.op, task.plan.taskId, started, false,
                HarnessI18n.get(locale, HarnessI18nKeys.TASK_STOP_DONE), task.ctx.cwd, locale,
                started ? FAILURE_CANCELLED : null));
    }

    /**
     * 本会话的后台任务随父代理这一轮结束：未结束的一律停下（排队的不再启动），结果定为已取消；
     * 把本轮全部后台任务的结果补回各自 task_start 的 {@link #META_SUBTASKS} 索引项并落父快照。重复调用无副作用。
     *
     * <p>两处调用：{@link #getInterceptor()} 在父代理运行结束时（落快照前，正常结束与人工审批暂停都走到这里）；
     * 引擎在每次运行结束、运行句柄注销之后再兜底一次（父代理异常退出时结束回调会被跳过）。宿主不要直接调用。</p>
     *
     * @since 4.2.0
     */
    @Internal
    public void endRound(@Nullable AgentSession parentSession) {
        final SubTaskQueue<AsyncTask> queue = SubTaskQueue.of(parentSession, false);
        if (queue == null) {
            return;
        }

        final List<AsyncTask> tasks = queue.drain();
        if (tasks.isEmpty()) {
            return;
        }

        // 收尾要落盘：带着中断标志写文件会被拒，先清掉、做完还回去
        final boolean interrupted = Thread.interrupted();
        int stopped = 0;
        try {
            for (AsyncTask task : tasks) {
                try {
                    if (task.result.isDone() == false) {
                        stopRunning(task, FAILURE_CANCELLED);
                        stopped++;
                    }
                    SessionRuns.detach(task.parentSession, task.link);
                    // 没启动过的（排队中被摘下、执行器不可用）还没有结果
                    task.plan.endIfOpen(STATUS_FAILURE, FAILURE_CANCELLED);
                    applyIndexStatus(task);
                } catch (Throwable e) {
                    LOG.warn("后台子任务收尾失败: task_id={}, 原因: {}", task.taskId, e.getMessage());
                }
            }

            parentSession.updateSnapshot();
        } catch (Throwable e) {
            LOG.warn("后台子任务收尾落盘失败: session={}, 原因: {}", parentSession.getSessionId(), e.getMessage());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (stopped > 0) {
            LOG.info("父代理本轮结束，已取消 {} 个未完成的后台子任务: session={}", stopped, parentSession.getSessionId());
        }
    }

    /**
     * 父代理运行结束时收尾本会话的后台任务（见 {@link #endRound}）。宿主不经 AgentFactory、
     * 自己给代理挂本才能时要一并挂上，否则后台任务要等引擎的兜底才取消，结果也补不回 task_start 的索引项。
     *
     * @since 4.2.0
     */
    public ReActInterceptor getInterceptor() {
        return roundInterceptor;
    }

    private final ReActInterceptor roundInterceptor = new AbsReActInterceptor() {
        @Override
        public void onAgentEnd(ReActTrace trace) {
            endRound(trace.getSession());
        }
    };

    /** 要续跑的任务正在后台排队或执行（新任务的 ID 是新生成的，不会撞上） */
    private boolean isQueuedOrRunning(AgentSession parentSession, TaskPlan plan) {
        if (plan.resumeSession == null) {
            return false;
        }
        SubTaskQueue<AsyncTask> queue = SubTaskQueue.of(parentSession, false);
        return runningTasks.containsKey(plan.taskId) || (queue != null && queue.isActive(plan.taskId));
    }

    private String busyResp(TaskPlan plan, Locale locale) {
        return formatTaskResp(plan.op, false,
                "ERROR: " + HarnessI18n.fmt(locale, HarnessI18nKeys.TASK_RESUME_BUSY, "task_id", plan.taskId));
    }

    /**
     * 停下一个已被 worker 取走（可能还没启动子代理）的后台任务，结果定为 {@code kind}。
     * 与 {@link #settleEnd}、{@link ParentStopLink} 同一把锁、同一口径：子代理已正常结束、只剩收尾的，等它的真实结果。
     *
     * @return 已正常结束的真实结果；null = 已按停止处理
     */
    private static @Nullable String stopRunning(AsyncTask task, String kind) {
        final boolean settled;
        synchronized (task.plan) {
            settled = task.plan.endSettled;
            if (settled == false) {
                // 返回给模型、写进索引与子会话的都是它（worker 之后跑完也不改）
                task.plan.end(STATUS_FAILURE, kind, true);
            }
        }

        if (settled) {
            String v = awaitSettled(task.result);
            if (v != null) {
                return v;
            }
            // 等不到（再次被中断或超时）：兜底按停止口径
            task.plan.end(STATUS_FAILURE, kind, true);
        }

        // 记下已停止（还没启动的不再启动），已启动的停下子代理
        task.link.dispose();
        return null;
    }

    /** 已结束任务的结果文本（异常完成时按执行失败给出） */
    private String resultOf(AsyncTask task) {
        try {
            String v = task.result.getNow(null);
            return v == null ? "" : v;
        } catch (Throwable e) {
            return formatTaskResp(task.plan.op, false, "ERROR: " + HarnessI18n.fmt(task.ctx.locale,
                    HarnessI18nKeys.TASK_ERROR_FAILED, "error", rootMessage(e)));
        }
    }

    /** 已结束的任务里按启动顺序的第一个；都没结束返回 null */
    private static @Nullable AsyncTask firstDone(List<AsyncTask> tasks) {
        for (AsyncTask task : tasks) {
            if (task.result.isDone()) {
                return task;
            }
        }
        return null;
    }

    /** 后台任务状态清单（task_wait 附带给模型：还有哪些没取回、各自到了哪一步） */
    private static String statusList(SubTaskQueue<AsyncTask> queue, List<AsyncTask> tasks) {
        StringBuilder buf = new StringBuilder("<background_tasks>\n");
        for (AsyncTask task : tasks) {
            String status = task.result.isDone() ? STATUS_COMPLETED
                    : (queue.isTaken(task) ? STATUS_RUNNING : STATUS_QUEUED);
            buf.append("<task><task_id>").append(escapeXml(task.taskId)).append("</task_id>")
                    .append("<description>").append(escapeXml(task.plan.op.description)).append("</description>")
                    .append("<status>").append(status).append("</status></task>\n");
        }
        return buf.append("</background_tasks>").toString();
    }

    /** task_start 的结果：任务标识与状态（运行中 / 排队中），加一句怎么取结果 */
    private static String renderStarted(TaskPlan plan, String status, Locale locale) {
        final String hint = STATUS_QUEUED.equals(status)
                ? HarnessI18n.get(locale, HarnessI18nKeys.TASK_START_QUEUED_HINT)
                : HarnessI18n.get(locale, HarnessI18nKeys.TASK_START_HINT);
        return "<task_started><task_id>" + escapeXml(plan.taskId) + "</task_id>"
                + "<description>" + escapeXml(plan.op.description) + "</description>"
                + "<agent_name>" + escapeXml(plan.op.agent_name) + "</agent_name>"
                + "<status>" + status + "</status></task_started>\n"
                + hint;
    }

    /**
     * 把后台任务的结果补回它那次 task_start 结果上的 {@link #META_SUBTASKS} 索引项（父线程里、轮次结束时调用）。
     * 找不到那条工具结果（取不到调用 id、已被压缩掉）就算了：宿主会退回读子会话里记的状态。
     */
    private static void applyIndexStatus(AsyncTask task) {
        final String callId = task.ctx.callId;
        if (callId == null || task.ctx.parentTrace == null || task.ctx.parentTrace.getWorkingMemory() == null) {
            return;
        }

        List<ChatMessage> messages = task.ctx.parentTrace.getWorkingMemory().getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ((message instanceof ToolMessage) == false || callId.equals(((ToolMessage) message).getToolCallId()) == false) {
                continue;
            }

            Object v = message.getMetadataAs(META_SUBTASKS);
            if (v instanceof List) {
                List<Object> next = new ArrayList<>();
                for (Object item : (List<?>) v) {
                    boolean mine = item instanceof Map && task.plan.taskId.equals(((Map<?, ?>) item).get("taskId"));
                    next.add(mine ? indexItemOf(task.plan) : item);
                }
                message.addMetadata(META_SUBTASKS, next);
            }
            return;
        }
    }

    /** 一个后台任务：独立的调用上下文（调用 id、停止标记）与父会话上的联动句柄 */
    private final class AsyncTask extends SubTaskQueue.Job {
        final CallContext ctx;
        final TaskPlan plan;
        final AgentSession parentSession;
        final ParentStopLink link;

        AsyncTask(CallContext ctx, TaskPlan plan, AgentSession parentSession) {
            super(plan.taskId);
            this.ctx = ctx;
            this.plan = plan;
            this.parentSession = parentSession;
            this.link = new ParentStopLink(ctx, plan);
        }

        /** worker 线程里跑子代理，跑完摘掉联动句柄 */
        @Override
        String run() {
            try {
                return runTask(ctx, plan);
            } finally {
                SessionRuns.detach(parentSession, link);
            }
        }

        @Override
        String skipped() {
            SessionRuns.detach(parentSession, link);
            return notStarted(plan.op, ctx.locale);
        }

        boolean succeeded() {
            return ctx.succeeded.get() > 0;
        }
    }

    /** 结果上挂子任务索引（只在任务确实跑起来时挂：被拒的续跑不挂，否则前端会按索引隐藏掉那条拒绝信息） */
    private static ToolResult withSubtasks(ToolResult result, TaskPlan plan) {
        if (plan.startedSession != null) {
            result.metas().put(META_SUBTASKS, indexOf(plan));
        }
        return result;
    }

    /** 索引值：一次调用一个任务，列表形态是契约（{@link #META_SUBTASKS}） */
    private static List<Map<String, Object>> indexOf(TaskPlan plan) {
        List<Map<String, Object>> list = new ArrayList<>(1);
        list.add(indexItemOf(plan));
        return list;
    }

    /** 子任务索引项（{@link #META_SUBTASKS} 与 {@link #EXTRA_RUNNING_SUBTASKS} 共用） */
    private static Map<String, Object> indexItemOf(TaskPlan plan) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", plan.taskId);
        item.put("agentName", plan.op.agent_name);
        item.put("description", plan.op.description);
        // 本次调用的结果（委派前登记运行中索引时还没有）
        String status = plan.endStatus;
        if (status != null) {
            item.put("status", status);
            String failureKind = plan.endFailureKind;
            if (failureKind != null) {
                item.put("failureKind", failureKind);
            }
        }
        return item;
    }

    /**
     * 委派前登记「这次调用 → 将要启动的子任务」并落一次父快照（见 {@link #EXTRA_RUNNING_SUBTASKS}）。
     *
     * <p>在调用线程里一次写好：taskId 在预解析时已确定。这次快照把正在执行的 lastReasonMessage
     * 一起存下（工具执行期间父会话本不保存），进程中途退出后宿主才有起点可回放。取不到调用 id（文本模式、测试直调）时不登记。
     * 失败只告警：索引只供展示，不能影响委派。</p>
     */
    private static void registerRunning(CallContext ctx, AgentSession parentSession, TaskPlan plan) {
        if (ctx.callId == null || plan.invalidReason != null || plan.agentBuilder == null) {
            return;
        }

        try {

            // 只保留当前这一步的调用：同一步里先跑完的 task 结果还没进工作记忆，它的索引不能丢
            Set<String> live = new HashSet<>();
            AssistantMessage lastReason = ctx.parentTrace.getLastReasonMessage();
            if (lastReason != null && lastReason.getToolCalls() != null) {
                for (ToolCall call : lastReason.getToolCalls()) {
                    live.add(call.getUuid());
                }
            }

            Map<String, Object> next = new LinkedHashMap<>();
            Object prev = ctx.parentTrace.getExtra(EXTRA_RUNNING_SUBTASKS);
            if (prev instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) prev).entrySet()) {
                    if (live.contains(String.valueOf(entry.getKey()))) {
                        next.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            next.put(ctx.callId, indexOf(plan));

            ctx.parentTrace.setExtra(EXTRA_RUNNING_SUBTASKS, next);
            parentSession.updateSnapshot();
        } catch (Throwable e) {
            LOG.warn("子任务索引登记失败: session={}, 原因: {}", ctx.sessionId, e.getMessage());
        }
    }

    // ========== 预解析 ==========

    /** 一个子任务的执行计划：预解析产物，供分派与执行共用 */
    static final class TaskPlan {
        final SingleTaskOp op;
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
        /**
         * 子代理已在其上跑起来的子会话（null = 还没跑起来；worker 线程写、调用线程读）。
         * 与 {@link CallContext#aborted} 的判定在同一把锁（本对象）里：停止后台任务或父代理被停止时，
         * 要么看到已启动（去停它），要么任务看到已放弃（不再启动），不会两边都落空。
         */
        volatile AgentSession startedSession;
        /** 本次调用的结果（写进 {@link #META_SUBTASKS} 索引项；null = 还没结束） */
        volatile String endStatus;
        volatile String endFailureKind;
        /** 结果已按停止定下（task_stop、轮次结束），之后 worker 迟到的结论不再覆盖 */
        private boolean endFrozen;
        /**
         * 子代理已正常结束且当时父代理未被停止（结束事件已转给前端）：结果按子代理的结论报，
         * 停止时不再冻结为失败，而是等它收尾（见 {@link #settleEnd}）。本对象锁内读写
         */
        private boolean endSettled;

        /**
         * 记下本次调用的结果。
         *
         * @param freeze 停止时调用：以返回给模型的结果为准，冻结后不再改
         */
        synchronized void end(String status, @Nullable String failureKind, boolean freeze) {
            if (endFrozen) {
                return;
            }
            this.endStatus = status;
            this.endFailureKind = failureKind;
            this.endFrozen = freeze;
        }

        /** 还没有结果时定为给定结果并冻结（轮次结束时给没启动过的任务补状态；已有结果的不动） */
        synchronized void endIfOpen(String status, @Nullable String failureKind) {
            if (endStatus == null) {
                end(status, failureKind, true);
            }
        }

        TaskPlan(SingleTaskOp op, ReActAgent.Builder agentBuilder, String invalidReason) {
            this(op, agentBuilder, invalidReason, null, null, null);
        }

        TaskPlan(SingleTaskOp op, ReActAgent.Builder agentBuilder, String invalidReason,
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

    private TaskPlan preparePlan(AgentSession session, SingleTaskOp op, String sessionModel, Locale locale) {
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
            // 走 invalidReason 而非裸文本：结果统一带 <task_result> 外壳
            return new TaskPlan(op, null, unknownAgentReason(op, locale));
        }

        return new TaskPlan(op, agentBuilder, null, ownSkills, ownMcps, resumeSession);
    }

    /**
     * 取回要续跑的子会话：ID 格式非法、不存在、不属于当前父会话、上次没跑起来（没有可续的进度）一律返回 null
     * （不区分原因，避免探测别的会话；只校验归属，不看上次成败）。
     * 成功时把 {@code op.agent_name} 改为原任务的代理（续跑以原任务为准）。
     */
    private AgentSession openForResume(AgentSession parentSession, SingleTaskOp op) {
        if (parentSession == null || isTaskId(op.task_id) == false) {
            return null;
        }

        final AgentSession sub;
        try {
            // 子会话挂在父会话之下：支持嵌套存储的提供者用别的父会话直接取不到
            sub = engine.getSession(op.task_id, parentSession.getSessionId(), false);
        } catch (Throwable e) {
            LOG.warn("子任务会话读取失败: task_id={}, 原因: {}", op.task_id, e.getMessage());
            return null;
        }

        if (sub == null) {
            return null;
        }

        // 仍校验元数据：不理会父会话标识的提供者（平铺存储）只能靠它守归属
        Map<String, Object> meta = subtaskMeta(sub);
        if (parentSession.getSessionId().equals(meta.get("parentSessionId")) == false) {
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

    private static String unknownAgentReason(SingleTaskOp op, Locale locale) {
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

    /**
     * 把子代理事件转给父流：先清掉本线程的中断标志，推完还回去。
     *
     * <p>父流是 Flux.create 建的，多线程推事件时由正在推的线程把排队的事件一起推下去，
     * 子任务线程因此可能替父代理写宿主的网络连接；带着停止留下的中断标志写（NIO 通道）会抛
     * ClosedByInterruptException，连接被直接关掉，宿主漏收本轮余下的事件。</p>
     */
    static void forward(FluxSink<AgentEvent> sink, AgentEvent event) {
        final boolean interrupted = Thread.interrupted();
        try {
            sink.next(event);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========== 停止与收尾 ==========

    /**
     * 等已定为正常结束的任务收尾（有上限，见 {@link #SETTLE_WAIT_MS}）。停止已经打断过本线程：
     * 先清掉中断标志再等，等完还回去。
     *
     * @return 结果；null = 等不到（超时或再次被中断）
     */
    private static @Nullable String awaitSettled(CompletableFuture<String> f) {
        boolean interrupted = Thread.interrupted();
        try {
            String v = f.get(SETTLE_WAIT_MS, TimeUnit.MILLISECONDS);
            return v == null ? "" : v;
        } catch (InterruptedException e) {
            interrupted = true;
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 子代理正常结束（收到结束事件）时定下这次的结果口径，只定一次：父代理已被停止 → 按停止报失败、
     * 结束事件照常转发、子 trace 记下停止类型（宿主经 {@link #failureKindOf} 显示「被中断」）；否则按子代理的结论报，之后停止也不再改判（{@link #stopRunning} 会等它收尾）。
     *
     * <p>与 {@link #stopRunning}、{@link ParentStopLink} 在同一把锁（plan）里判定：前端看到的、模型收到的、
     * 子会话记下的三者同一口径。</p>
     *
     * @return true = 父代理已被停止（本次按停止报失败）
     */
    private static boolean settleEnd(CallContext ctx, TaskPlan plan) {
        synchronized (plan) {
            if (plan.endSettled) {
                return false;
            }
            if (plan.endFrozen || ctx.isParentStopped()) {
                return true;
            }
            plan.endSettled = true;
            return false;
        }
    }

    /**
     * 单个 task 挂在父会话上的联动句柄。
     *
     * <p>子代理与父代理同线程，运行句柄却登记在子会话上：父会话的停止（三种方式都会停掉父会话上登记的全部句柄，
     * 其中 cancelActive 不推进取消代数）经它联动：记下「父代理已停止」，未启动的不再启动，已启动且未定结果的按取消停下
     * （不推结束事件）。与 {@link #stopRunning} 同一把锁、同一口径。</p>
     */
    private static final class ParentStopLink implements Disposable {
        private final CallContext ctx;
        private final TaskPlan plan;
        private final AtomicBoolean disposed = new AtomicBoolean();

        ParentStopLink(CallContext ctx, TaskPlan plan) {
            this.ctx = ctx;
            this.plan = plan;
        }

        @Override
        public void dispose() {
            if (disposed.compareAndSet(false, true) == false) {
                return;
            }

            final AgentSession started;
            synchronized (plan) {
                ctx.aborted = true;
                started = plan.endSettled ? null : plan.startedSession;
            }

            if (started != null) {
                SessionRuns.cancelAll(started);
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    /**
     * 子代理每一步前看一眼是否已被停止（推理前、每个工具调用前），已停止就结束本次运行。
     *
     * <p>停止本身只是中断子代理线程：工具若吞掉中断（或卡在不可中断的调用里），子代理会接着推理、调工具，
     * 停不下来。结束方式与模型调用被中断时一致（路由转到结束），结果仍按停止口径报、可带 task_id 接着做。</p>
     */
    private static final class StopGuard extends AbsReActInterceptor {
        private final CallContext ctx;

        StopGuard(CallContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
            endIfStopped(trace);
        }

        @Override
        public void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
            endIfStopped(trace);
        }

        private void endIfStopped(ReActTrace trace) {
            if (ctx.isParentStopped()) {
                trace.setRoute(Agent.ID_END);
            }
        }
    }

    /** 一次委派调用（task / task_start）的上下文：调用 id、视野、语言与停止判定（避免 runTask 参数过长） */
    private static final class CallContext {
        private final ReActTrace parentTrace;
        /** 发起本次委派的父工具调用 id（在调用线程里取；取不到为 null） */
        private final String callId;
        /** 后台任务（task_start）；同步 task 为 false（见 {@link TaskWrapEvent#isBackground()}） */
        private final boolean background;
        private final String cwd;
        private final String sessionId;
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
        /**
         * 后台任务被停止（task_stop、轮次结束），或联动句柄已触发（父会话被停止）：
         * 还没启动的任务不再启动（见 {@link TaskPlan#startedSession}）
         */
        private volatile boolean aborted;
        /** 父会话与停止判定基准（入口处取的取消代数）；null = 不按代数判定 */
        private volatile AgentSession parentSession;
        private volatile int stopGen;
        /** 收尾落盘前清掉过本线程的中断标志（单个 task 据此还给父线程） */
        private volatile boolean interruptCleared;

        CallContext(ReActTrace parentTrace, String cwd, String sessionId, boolean background) {
            this.parentTrace = parentTrace;
            this.callId = parentTrace.getActingCallId();
            this.background = background;
            this.cwd = cwd;
            this.sessionId = sessionId;
            // 在调用线程里只解析一次：后台任务在 worker 线程里跑，取不到父代理的请求上下文
            this.skillsSelected = SkillTalent.getSelected(parentTrace.getOptions().getToolContext());
            this.mcpsSelected = McpGatewayTalent.getSelected(parentTrace.getOptions().getToolContext());
            this.scope = InputScope.of(parentTrace);
            this.locale = parentTrace.getOptions().getLocale();
        }

        /** 记下父会话与入口处取的取消代数：之后推进过即父代理已被停止 */
        void watchParent(AgentSession parentSession, int stopGen) {
            this.stopGen = stopGen;
            this.parentSession = parentSession;
        }

        /** 父代理已被停止：任务已被停止 / 联动句柄已触发、父流已取消、或入口之后父会话被取消过 */
        boolean isParentStopped() {
            if (aborted || isCancelled(streamSinkOf(parentTrace))) {
                return true;
            }
            AgentSession parent = parentSession;
            return parent != null && SessionRuns.isCancelledSince(parent, stopGen);
        }

        /** 停止的失败类型：父流已取消为 cancelled，其余（打断父线程）为 interrupted */
        String stopKind() {
            return isCancelled(streamSinkOf(parentTrace)) ? FAILURE_CANCELLED : FAILURE_INTERRUPTED;
        }

        /** 收尾落盘前清掉本线程的中断标志：带着它写文件会被拒（ClosedByInterruptException），子会话状态就写不进去 */
        void clearInterrupt() {
            if (Thread.interrupted()) {
                interruptCleared = true;
            }
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

    /** 未启动的任务结果（父代理已停止、排队中被摘下、执行器不可用） */
    private String notStarted(SingleTaskOp task, Locale locale) {
        return formatTaskResp(task, false, notStartedReason(locale));
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

    private String runTask(CallContext ctx, TaskPlan plan) {
        if (plan.invalidReason != null) {
            return formatTaskResp(plan.op, false, plan.invalidReason);
        }

        if (plan.agentBuilder == null) {
            // 保底：统一走外壳，不返回裸文本
            return formatTaskResp(plan.op, false, unknownAgentReason(plan.op, ctx.locale));
        }

        final String taskId = plan.taskId;

        // 运行锁与按父会话登记一步完成（见 runningTasks）。先于建子会话：父会话在此之后就不能被删
        if (runningTasks.putIfAbsent(taskId, ctx.sessionId) != null) {
            return formatTaskResp(plan.op, false,
                    "ERROR: " + HarnessI18n.fmt(ctx.locale, HarnessI18nKeys.TASK_RESUME_BUSY, "task_id", taskId));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务开始[{}]: {}", plan.op.agent_name, ONode.serialize(plan.op));
        }

        final boolean resume = plan.resumeSession != null;
        AgentSession sub = null;
        // 已把子代理的结束事件转给父流（被停止时没有就补一个，见 forwardStopEnd）
        final AtomicBoolean endForwarded = new AtomicBoolean();
        String traceKey = null;

        try {
            // 标记委派深度：本线程内再触发 task / task_start 会被拒绝
            TASK_DEPTH.set(Boolean.TRUE);

            final ReActAgent agent = plan.agentBuilder.build();
            traceKey = agent.getConfig().getTraceKey();

            final AgentSession session;
            final Metrics baseline;
            final Flux<AgentEvent> events;

            // 启动与停止互斥（见 TaskPlan#startedSession）。先建好流再登记已启动：之后的停止会推进子会话的取消代数，
            // 这条流订阅时直接放弃（见 ReActRequest#stream），不留「已登记、未订阅」时停不下来的窗口
            synchronized (plan) {
                if (ctx.isParentStopped()) {
                    return notStarted(plan.op, ctx.locale);
                }

                session = resume ? plan.resumeSession : engine.getSession(taskId, ctx.sessionId, true);
                sub = session;

                final ReActTrace prior = session.getContext().getAs(traceKey);
                final ReActRequest request;
                final int maxTurns;

                if (resume) {
                    // 上次按停止结束留下的标记：这次重新开始，不能沿用
                    prior.getExtras().remove(EXTRA_STOP_KIND);
                    // 封口：上一次调用的消息还没打戳，趁 markRunning 改写 parentCallId 之前按它补上，
                    // 否则会被算到本次调用头上（随 markRunning 的快照一起落盘）
                    sealRun(prior, asString(subtaskMeta(session).get("parentCallId")));
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

                markRunning(session, ctx.sessionId, ctx.callId, plan.op, traceKey);
                // 子 trace 的指标是累计的：续跑只向父 trace 汇总本次增量
                baseline = prior == null ? null : prior.getMetrics().snapshot();

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

                    o.interceptorAdd(new StopGuard(ctx));
                });

                events = request.stream();
                plan.startedSession = session;
            }

            final FluxSink<AgentEvent> sink = streamSinkOf(ctx.parentTrace);
            final RunEndEvent agentEnd;
            // 结束时父代理已被停止（在收到结束事件那一刻判定一次，见 settleEnd）
            final AtomicBoolean stopped = new AtomicBoolean();

            if (sink == null) {
                // 同步模式
                agentEnd = (RunEndEvent) events.blockLast();
                // 没有前端要转发：结束后判定一次
                if (agentEnd != null && settleEnd(ctx, plan)) {
                    stopped.set(true);
                    markStopKind(agentEnd, ctx, plan);
                }
            } else {
                // 流式模式
                agentEnd = (RunEndEvent) events
                        .takeUntil(r -> sink.isCancelled())
                        .doOnNext(chunk -> {
                            // 父代理已被停止：这次按停止报失败。结束事件照常转发，先在子 trace 上记下停止类型，
                            // 宿主经 failureKindOf 读到后实时显示「被中断」（与回放、返回给模型的同一口径）。只判定一次
                            if (chunk instanceof RunEndEvent && settleEnd(ctx, plan)) {
                                stopped.set(true);
                                markStopKind((RunEndEvent) chunk, ctx, plan);
                            }
                            // 父流已取消时停止向主会话灌 chunk
                            if (sink.isCancelled()) {
                                return;
                            }
                            // 后台任务与父代理会并发推事件：父流是 Flux.create 建的，它的 sink 已对 next 串行化
                            forward(sink, new TaskWrapEvent(ctx.parentTrace, ctx.callId, taskId, plan.op, ctx.background, chunk));
                            if (chunk instanceof RunEndEvent) {
                                endForwarded.set(true);
                            }
                        })
                        .blockLast();
            }

            // 以下收尾要落盘：先清掉停止留下的中断标志（单个 task 在 task() 里还回去）
            ctx.clearInterrupt();

            requireEnd(agentEnd, sink, ctx.locale);

            if (ctx.parentTrace != null) {
                Metrics metrics = agentEnd.getMetrics();
                ctx.parentTrace.getMetrics().addMetrics(baseline == null ? metrics : metrics.deltaSince(baseline));
            }

            if (stopped.get()) {
                // 父代理已被停止：子代理是被同一次停止打断后「正常结束」的（结果不可信），与 task_stop / 轮次结束同一口径
                stopCommandsOf(session);
                final String kind = ctx.stopKind();
                plan.end(STATUS_FAILURE, kind, false);
                markEnded(session, plan);
                String note = "ERROR: " + stopNote(kind, ctx.locale);
                return startedResp(ctx, plan.op, taskId, false, note, kind);
            }

            // 模型服务失败 / 回合用尽：子代理是「正常结束」的，但任务没完成，要报失败才会被重试
            final String failureKind = failureKindOf(agentEnd);
            if (failureKind != null) {
                LOG.warn("任务失败[{}]: task_id={}, kind={}", plan.op.agent_name,
                        taskId, failureKind);
                plan.end(STATUS_FAILURE, failureKind, false);
                markEnded(session, plan);
                return startedResp(ctx, plan.op, taskId, false, "ERROR: " + agentEnd.getText(), failureKind);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("任务成功[{}]: {}", plan.op.agent_name,
                        plan.op.description);
            }

            // 超长结果落盘到本轮会话工作目录：模型的文件工具按 __cwd 解析相对路径
            // 先拼好结果再计数：拼结果时抛异常会走失败分支，不会误计
            String resp = startedResp(ctx, plan.op, taskId, true, agentEnd.getText(), null);
            plan.end(STATUS_SUCCESS, null, false);
            markEnded(session, plan);
            ctx.succeeded.incrementAndGet();
            return resp;
        } catch (Throwable e) {
            // 收尾要落盘（见正常出口）
            ctx.clearInterrupt();
            // 父代理已被停止：子代理被同一次停止取消（不推结束事件），这不是执行失败
            final boolean stoppedByParent = ctx.isParentStopped();
            if (stoppedByParent) {
                LOG.debug("任务随父代理停止[{}]: {}", plan.op.agent_name, e.getMessage());
                stopCommandsOf(sub);
            } else {
                LOG.error("任务失败[{}]: {}", plan.op.agent_name,
                        e.getMessage(), e);
            }
            LlmErrorEvent.Type type = LlmErrorClassifier.classify(e);

            String reason = "ERROR: " + HarnessI18n.fmt(ctx.locale, HarnessI18nKeys.TASK_ERROR_FAILED, "error", e.getMessage());

            // 子代理已跑起来（有原始提示词）才给 task_id：进度在子会话里
            ReActTrace trace = (sub == null || traceKey == null) ? null : sub.getContext().getAs(traceKey);
            if (trace != null && Prompt.isEmpty(trace.getOriginalPrompt()) == false) {
                // 父代理已被停止：按停止口径报（否则同一次停止会因时序报成 error，回放显示红叉）
                String kind = stoppedByParent ? ctx.stopKind()
                        : (type == null ? "error" : type.value());
                if (stoppedByParent) {
                    reason = "ERROR: " + stopNote(kind, ctx.locale);
                }
                plan.end(STATUS_FAILURE, kind, false);
                markEnded(sub, plan);
                if (stoppedByParent && endForwarded.get() == false) {
                    forwardStopEnd(ctx, plan, sub, trace, reason);
                }
                return startedResp(ctx, plan.op, taskId, false, reason, kind);
            }

            plan.end(STATUS_FAILURE, type == null ? "error" : type.value(), false);
            return formatTaskResp(plan.op, false, reason);
        } finally {
            // 必须清理：池线程会被复用，残留标记会误伤后续任务
            TASK_DEPTH.remove();
            runningTasks.remove(taskId);
        }
    }

    /**
     * 子任务被停止（task_stop、轮次结束、父代理被停止）时，连带结束子代理用 bash_start 启动、还在跑的命令：
     * 否则进程会一直跑到硬超时。正常结束、执行失败的不动（子代理可能有意留着，如启动的服务）。
     */
    private void stopCommandsOf(@Nullable AgentSession sub) {
        final TerminalTalent terminal = engine.getTerminalTalent();
        if (sub == null || terminal == null) {
            return;
        }
        try {
            int n = terminal.stopCommandsOf(sub.getSessionId(), "task stopped");
            if (n > 0) {
                LOG.info("子任务已停止，结束其 {} 个后台命令: session={}", n, sub.getSessionId());
            }
        } catch (Throwable e) {
            LOG.warn("结束子任务后台命令失败: session={}, 原因: {}", sub.getSessionId(), e.getMessage());
        }
    }

    /**
     * 失败类型是否「被停止」（task_stop、轮次结束、父代理被停止）：宿主据此显示「被中断」而不是失败。
     *
     * @since 4.2
     */
    public static boolean isStopKind(@Nullable String failureKind) {
        return FAILURE_CANCELLED.equals(failureKind) || FAILURE_INTERRUPTED.equals(failureKind);
    }

    /**
     * 被停止时子代理流被直接取消、没有结束事件：补一个转给父流（带停止类型），宿主才能当场把这次调用显示为「被中断」，
     * 而不是一直转圈、到轮次结束按完成画绿勾。父流已取消（用户停止父代理）时不补，宿主自己结算。
     */
    private static void forwardStopEnd(CallContext ctx, TaskPlan plan, AgentSession sub, ReActTrace trace, String note) {
        final FluxSink<AgentEvent> sink = streamSinkOf(ctx.parentTrace);
        if (sink == null || sink.isCancelled()) {
            return;
        }
        try {
            RunEndEvent end = new RunEndEvent(trace, new ReActResponse(sub, trace, ChatMessage.ofAssistant(note)));
            markStopKind(end, ctx, plan);
            forward(sink, new TaskWrapEvent(ctx.parentTrace, ctx.callId, plan.taskId, plan.op, ctx.background, end));
        } catch (Throwable e) {
            LOG.warn("子任务停止事件转发失败: task_id={}, 原因: {}", plan.taskId, e.getMessage());
        }
    }

    /** 在结束事件的子 trace 上记下停止类型（已按停止冻结的取冻结的那个，如 task_stop 为 cancelled） */
    private static void markStopKind(RunEndEvent end, CallContext ctx, TaskPlan plan) {
        final ReActTrace trace = end.getTrace();
        if (trace == null) {
            return;
        }
        final String kind;
        synchronized (plan) {
            kind = (plan.endFrozen && plan.endFailureKind != null) ? plan.endFailureKind : ctx.stopKind();
        }
        trace.setExtra(EXTRA_STOP_KIND, kind);
    }

    /** 随父代理停止的说明：取消与中断各一句 */
    private static String stopNote(String kind, Locale locale) {
        if (FAILURE_CANCELLED.equals(kind)) {
            return HarnessI18n.get(locale, HarnessI18nKeys.TASK_STOPPED_CANCELLED);
        } else {
            return HarnessI18n.get(locale, HarnessI18nKeys.TASK_STOPPED_INTERRUPTED);
        }
    }

    /**
     * 子代理跑起来过的结果：成败都带 {@code <task_id>}（可接着问或从断点重试），失败另带 {@code <failure_kind>}
     *
     * @param failureKind 失败类型（成功为 null）
     */
    private String startedResp(CallContext ctx, SingleTaskOp op, String taskId, boolean successful, String result,
                               @Nullable String failureKind) {
        return renderTaskResp(op, taskId, true, successful, result, ctx.cwd, ctx.locale, failureKind);
    }

    /**
     * 失败类型：被停止为 {@code cancelled} / {@code interrupted}（见 {@link #isStopKind}），模型服务错误取其分类值，
     * 回合用尽为 {@code max_turns}；成功返回 null。
     *
     * <p>不看 {@code isAbnormal()}：returnDirect 工具与 Feedback 也会标异常结束，但任务是完成了的。</p>
     *
     * @since 4.2
     */
    public static String failureKindOf(RunEndEvent agentEnd) {
        ReActTrace stoppedTrace = agentEnd.getTrace();
        Object stopKind = stoppedTrace == null ? null : stoppedTrace.getExtra(EXTRA_STOP_KIND);
        if (stopKind instanceof String) {
            return (String) stopKind;
        }

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
     * 留着正好让子代理知道上次为什么停。</p>
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

    /**
     * 封口：从尾部往前，给还没打 {@link #META_PARENT_CALL_ID} 的消息补上 callId，碰到已打戳的就停（之前的都已封过）。
     *
     * <p>只在续跑开始前调用一次（callId 取上一次调用的 parentCallId）：任何时刻只有最近一次调用的消息没戳，
     * 夹在已打戳消息之间、没戳的只有压缩摘要。</p>
     */
    static void sealRun(@Nullable ReActTrace trace, @Nullable String callId) {
        if (trace == null || callId == null || trace.getWorkingMemory() == null) {
            return;
        }

        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.hasMetadata(META_PARENT_CALL_ID)) {
                break;
            }
            message.addMetadata(META_PARENT_CALL_ID, callId);
        }
    }

    private static String asString(Object v) {
        return v instanceof String ? (String) v : null;
    }

    /** 登记子任务信息并落一次快照（进程重启后仍可校验归属；running 留到结束前，崩溃后宿主按「已中断」展示） */
    private static void markRunning(AgentSession sub, String parentSessionId, @Nullable String parentCallId,
                                    SingleTaskOp op, String traceKey) {
        Map<String, Object> meta = new LinkedHashMap<>(subtaskMeta(sub));
        meta.put("parentSessionId", parentSessionId);
        if (parentCallId != null) {
            meta.put("parentCallId", parentCallId);
        } else {
            meta.remove("parentCallId");
        }
        meta.put("agentName", op.agent_name);
        meta.put("traceKey", traceKey);
        meta.put("description", op.description);
        meta.put("status", STATUS_RUNNING);
        meta.remove("failureKind");
        sub.getContext().put(META_SUBTASK, meta);
        sub.updateSnapshot();
    }

    /** 记下结束状态：以 plan 定下的结果为准（停止时已冻结为返回给模型的失败，子会话与之一致） */
    private static void markEnded(AgentSession sub, TaskPlan plan) {
        final String status;
        final String failureKind;
        synchronized (plan) {
            status = plan.endStatus;
            failureKind = plan.endFailureKind;
        }

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

    String formatTaskResp(SingleTaskOp task, boolean successful, String result) {
        return formatTaskResp(task, Utils.uuid(), successful, result);
    }

    String formatTaskResp(SingleTaskOp task, String taskId, boolean successful, String result) {
        return formatTaskResp(task, taskId, successful, result, null);
    }

    /**
     * @param cwd 父会话本轮工作目录（超长结果落盘位置；空表示工作区根）
     */
    String formatTaskResp(SingleTaskOp task, String taskId, boolean successful, String result, String cwd) {
        return formatTaskResp(task, taskId, successful, result, cwd, null);
    }

    /**
     * @param locale 截断说明的语言（null 为默认语言）
     */
    String formatTaskResp(SingleTaskOp task, String taskId, boolean successful, String result, String cwd, Locale locale) {
        return renderTaskResp(task, taskId, false, successful, result, cwd, locale, null);
    }

    /**
     * @param showTaskId  是否输出 {@code <task_id>}：只有子代理跑起来过才给（没跑起来的任务没有子会话可接）
     * @param failureKind 失败类型（null = 不输出）
     */
    private String renderTaskResp(SingleTaskOp task, String taskId, boolean showTaskId, boolean successful, String result,
                                  String cwd, Locale locale, @Nullable String failureKind) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_result>");
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
    private String toCdata(SingleTaskOp op, String taskId, String text, String cwd, Locale locale) {
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
    private String trySpill(SingleTaskOp op, String taskId, String text, String cwd) {
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
        @Param(name = "task_id", required = false, description = "可选。接着之前的任务做（取自结果里的 <task_id>，成败均可）：子代理保留之前的全部上下文。失败时从断点重试，prompt 填“继续”或修正说明；完成后可追问或补充要求，prompt 填后续指令。agent_name 以原任务为准。新任务不要填。")
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
}
