/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.lang.Internal;
import org.noear.solon.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一次输入的处置结果
 * <p>
 * {@link org.noear.solon.ai.harness.HarnessEngine#handle} 的返回值：把「命令识别 / 子代理解析 /
 * 附件装配 / Prompt 构建 / 请求装配」的结果一次性交给宿主。
 *
 * <p>宿主只有一种取用方式：读 {@link #getKind()} 做一次 switch —— 一个值对应一个动作。
 * 需要执行任务时，流来自 {@code getRequest().stream()}（宿主自己决定订阅时机），
 * 回执来自 {@link #getCommandOutput()}（宿主自己决定渲染顺序）。
 * 不做额外准备时可用 {@link #call()} / {@link #stream()} 直接收束（仅转发，不改语义）。</p>
 *
 * <p><b>刻意不提供「回执 + 任务合成一条流」的便捷方法。</b>真实宿主对回执与任务的顺序要求各不相同：
 * CLI 由命令即时写终端、Web 要求「流门复位早于回执」、桌面要求序号回放与 HITL 拦截器重挂、
 * ACP 需要 {@code takeWhile} 取消判定。任何替宿主拼好的流都得被拆开重拼，
 * 反而遮住了这些必须由宿主掌握的时序。</p>
 *
 * <p>命名沿用本产品既有的 {@code *Outcome} 家族（{@code RunOutcome}、{@code PollOutcome}）：
 * 都是「一次处理后归入几类结局」的同一类问题。{@code TASK} 表示
 * 「处置决定是交给你执行」、尚未执行 —— 所以不是 {@code Result}（后者在本产品专指 HTTP 响应包装）。</p>
 *
 * <p><b>kind 只回答「宿主该做什么」，不回答「输入是什么」。</b>
 * 「这条输入是不是命令」属于事实，由 {@link #isCommand()} / {@link #getCommandName()} 读回 ——
 * 因此命令触发的任务（{@code TASK} + {@code isCommand()==true}）与用户手输的任务走同一个分支，
 * 差异只体现在「有没有回执」这份数据上，不需要宿主写两段代码。</p>
 *
 * <p>本类不依赖 {@code HarnessEngine}，也不做任何线程调度决策 —— 能力靠注入而非反向依赖。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class InputOutcome {
    /**
     * 宿主该做什么
     * <p>
     * 六个值互斥且穷尽：每个值都是「本次输入已经发生的事」的一个真实结局。
     * 新增能力（如命令权限门控）不会新增值，只会落到既有结局上；
     * {@link #DEFERRED} 与 {@link #REJECTED} 是受理判定（{@code handle} 第 0 步）的两种结局——
     * 「排队了」与「被拒了」宿主的处理不同（如 IM 要回报不同状态），不能并入 {@link #REPLY}。
     */
    public enum Kind {
        /**
         * 有可执行的任务：宿主执行 {@link #getRequest()}
         * <p>
         * 若 {@link #isCommand()} 为 true（命令触发的任务），{@link #getCommandOutput()} 是可供
         * 前置渲染的回执；回执与任务并存，渲染后继续跑任务。
         */
        TASK,
        /**
         * 只有回执，没有任务：宿主渲染 {@link #getCommandOutput()} 后收尾
         * <p>
         * 涵盖「命令已执行」与「命令在此渠道不可用（未执行）」两种事实，
         * 后者用 {@link #isCommand()} 仍为 true 表示；宿主对二者动作相同。
         */
        REPLY,
        /**
         * 只有决策相关的事实需要宿主处理，本次未续流
         * <p>
         * 本类型不携带请求。三种进入方式：
         * <ul>
         * <li>未传 {@code resume} —— 只写决策，续流时机由宿主决定；</li>
         * <li>传了 {@code resume} 但本批仍有未决卡 —— 引擎拒绝过早续流（会带走未决任务），
         * 宿主应等待其余卡；剩余未决数量读 {@link #getPendingHitlCount()}，
         * 据此可展示下一张卡（桌面端逐卡交互即此形态）；</li>
         * <li>传了 {@code resume} 但决策未命中任何挂起任务（过期审批卡）——
         * 判据为 {@link #isHitlApplied()} 返回 {@code false}，宿主应提示卡已失效，
         * 切勿续流（否则空提示词会回落原始提问，把上一轮问题重跑一遍）。</li>
         * </ul>
         * <p>
         * 单卡同步审批（Web、桌面批末最后一卡、CLI 单卡批）不经过本类型：
         * {@code .hitl(...).resume(true)} 一次调用即返回 {@link #TASK}。
         * <p>
         * 宿主处理契约：本类型不作“继续”或“终止”的断言，宿主动作必须读事实：
         * <ul>
         * <li>{@link #isHitlApplied()} 为 {@code false} —— 过期卡，提示失效后丢弃；</li>
         * <li>否则 {@link #getPendingHitlCount()} &gt; 0 —— 展示/等待下一张卡（具体展示方式各端不同：
         * Web 首次挂起时已推送全部卡，无需再推；桌面与 CLI 需主动推下一张）。</li>
         * </ul>
         */
        HITL,
        /**
         * 输入为空（无文本也无附件），无需处理
         */
        IGNORED,
        /**
         * 会话忙（有运行流 / 正在受理另一条输入 / 积压里还有待执行输入），本输入已进入积压，
         * 空闲后续发。位置读 {@link #getBacklogPosition()}（从 1 计）。
         * <p>
         * 只有引擎装了积压能力（{@code InputBacklog}，如 queue 插件）且输入可排队
         * （纯文本、非程序轮次、非审批决策、非续流）时才会给出。续发由宿主触发（执行在宿主那里）。
         */
        DEFERRED,
        /**
         * 未受理：宿主渲染 {@link #getRejectDetail()} 后收尾。原因读 {@link #getRejectReason()}（见 {@link RejectReason}）。
         */
        REJECTED
    }

    /**
     * 拒绝原因（仅 {@link Kind#REJECTED}）
     *
     * <p>{@link #code()} 是线上协议值（小写下划线，宿主透传给前端即可）；宿主分支一律按枚举比较。</p>
     *
     * @since 4.2.0
     */
    public enum RejectReason {
        /** 有待审批卡，本输入不是审批决策 / 续流 / 忙时可执行命令 */
        HITL_PENDING("hitl_pending"),
        /** 会话忙且无处可延（没装积压能力、程序轮次、续流等不可排队的输入） */
        BUSY("busy"),
        /** 会话忙且输入带附件（附件不进积压） */
        ATTACHMENTS_NOT_DEFERRABLE("attachments_not_deferrable"),
        /** 会话忙，积压拒收（已满 / 超长 / 已停用） */
        BACKLOG_UNAVAILABLE("backlog_unavailable"),
        /** 多节点：会话由其它节点持有（{@code getRejectDetail()} 为持有者节点标识，可空），由网关转发 */
        NOT_OWNER("not_owner"),
        /** 要求作用域（{@code scopeRequired}）但本轮解析不到（解析器返回 null 或抛异常） */
        SCOPE_UNAVAILABLE("scope_unavailable"),
        /** 会话忙，且输入显式携带的作用域含不可保存的维度（模型实例 / 任意属性），不能排队 */
        SCOPE_NOT_DEFERRABLE("scope_not_deferrable"),
        /** 引擎已关闭（{@code shutdown} 之后）：宿主持有的是过期引用，应重新从引擎池取 */
        ENGINE_CLOSED("engine_closed"),
        /**
         * 会话不存在。
         *
         * <p>引擎的 {@code handle} 不会给出（它总是带着会话来）；由宿主层在「只查不建」取会话失败时使用
         * （如 {@code HarnessHost}），放在同一张码表里，宿主只需一处映射。</p>
         */
        SESSION_NOT_FOUND("session_not_found");

        private final String code;

        RejectReason(String code) {
            this.code = code;
        }

        /** 线上协议值（小写下划线） */
        public String code() {
            return code;
        }
    }

    /** 宿主动作路由值（六值互斥穷尽，语义见 {@link Kind}） */
    private final Kind kind;
    /** 命令名；非 null 即命令输入（含被渠道拦截未执行的情况），是 {@link #isCommand()} 的判据 */
    private final String commandName;
    /** 回执文本（命令输出或不可用提示）；null 表示无回执（{@link #hasReply()} 为 false） */
    private final String commandOutput;
    /** 原始命令解析结果，供宿主做 rewind 等特判；非命令输入为 null */
    private final ParsedCommand command;
    /** 装配好的提示词；非消息路径（续流任务、纯回执）为 null */
    private final Prompt prompt;
    /** 生效的子代理名（HITL 分支为 resolveHitlAgent 的解析结果）；主代理时为 null */
    private final String agentName;
    /** 生效的模型名（显式入参 → 会话级选择解析后的值）；均未设置时为 null */
    private final String modelName;
    /** 装配好的请求；仅 {@link Kind#TASK} 非空，是 {@link #hasRequest()} 的判据 */
    private final ReActRequest request;
    /** 审批决策是否真的写入（null = 本次输入不是审批决策）；false 表示无匹配的挂起任务 */
    private final Boolean hitlApplied;
    /** 决策写入后仍待审批的挂起项数量（仅审批决策路径有意义，其余为 0） */
    private final int pendingHitl;
    /** 本批挂起卡快照（仅审批决策路径有意义；其余为空集合，永不为 null） */
    private final List<HitlCard> hitlCards;
    /** 积压位置（仅 {@link Kind#DEFERRED}，从 1 计；其余为 0） */
    private int backlogPosition;
    /** 拒绝原因与展示文字（仅 {@link Kind#REJECTED}） */
    private RejectReason rejectReason;
    private String rejectDetail;
    /** 本条（忙时可执行命令）是否在正在运行的任务旁边执行 */
    private boolean besideRunning;
    /** 本轮所属的程序派发事务（如积压续发）；非派发为 null */
    private RunDispatch dispatch;
    /** 放弃执行时释放受理占位（仅 TASK 且占到了位时非空；幂等） */
    private Runnable discarder;
    /** 登记「本轮运行句柄注销之后」的回调（仅 TASK 且请求由引擎装配时非空） */
    private Consumer<Runnable> afterRunRegistrar;

    private InputOutcome(Kind kind, String commandName, String commandOutput,
                        ParsedCommand command, Prompt prompt, String agentName, String modelName,
                        ReActRequest request, Boolean hitlApplied, int pendingHitl,
                        List<HitlCard> hitlCards) {
        this.kind = kind;
        this.commandName = commandName;
        this.commandOutput = commandOutput;
        this.command = command;
        this.prompt = prompt;
        this.agentName = agentName;
        this.modelName = modelName;
        this.request = request;
        this.hitlApplied = hitlApplied;
        this.pendingHitl = pendingHitl;
        this.hitlCards = hitlCards == null ? Collections.emptyList() : hitlCards;
    }

    // ========== 工厂（@Internal：只由引擎与命令分发器构造，宿主只读不建） ==========

    /**
     * 有可执行的任务（普通消息或续流）：宿主执行装配好的请求
     */
    @Internal
    public static InputOutcome task(Prompt prompt, String agentName, String modelName, ReActRequest request) {
        return new InputOutcome(Kind.TASK, null, null, null, prompt, agentName, modelName, request, null, 0, null);
    }

    /**
     * 有可执行的任务，且本次输入是审批决策（携带决策写入结果）
     * <p>
     * 能续流即意味着本批已齐备，故 {@code pendingHitl} 恒为 0（非“未统计”）。
     */
    @Internal
    public static InputOutcome task(Prompt prompt, String agentName, String modelName, ReActRequest request,
                                   boolean hitlApplied) {
        return new InputOutcome(Kind.TASK, null, null, null, prompt, agentName, modelName, request,
                hitlApplied, 0, null);
    }

    @Internal
    public static InputOutcome ignored() {
        return new InputOutcome(Kind.IGNORED, null, null, null, null, null, null, null, null, 0, null);
    }

    /**
     * 已进入积压（会话忙）
     *
     * @param position 积压位置（从 1 计）
     * @since 4.2.0
     */
    @Internal
    public static InputOutcome deferred(int position) {
        InputOutcome outcome = new InputOutcome(Kind.DEFERRED, null, null, null, null, null, null, null, null, 0, null);
        outcome.backlogPosition = position;
        return outcome;
    }

    /**
     * 未受理
     *
     * @param reason 原因
     * @param detail 可展示的文字
     * @since 4.2.0
     */
    @Internal
    public static InputOutcome rejected(RejectReason reason, @Nullable String detail) {
        InputOutcome outcome = new InputOutcome(Kind.REJECTED, null, null, null, null, null, null, null, null, 0, null);
        outcome.rejectReason = reason;
        outcome.rejectDetail = detail;
        return outcome;
    }

    /**
     * 带上受理事实的副本（引擎内部使用：{@code handle} 第 0 步之后调用；宿主不要调用）。
     *
     * <p>返回新实例、不改写本实例：宿主即便调用，也只影响自己拿到的副本，
     * 引擎交出去的那份（及其占位释放器）不受影响——「占位跟着请求走」的不变量不会被外部破坏。</p>
     *
     * @param besideRunning 是否在正在运行的任务旁边执行（忙时可执行命令）
     * @param dispatch      本轮所属的程序派发事务（可为 null）
     * @param discarder     放弃执行时释放受理占位的动作（可为 null）
     * @param afterRunRegistrar 登记「运行句柄注销之后」回调的动作（可为 null，见 {@link #afterRun(Runnable)}）
     * @return 新实例
     * @since 4.2.0
     */
    @Internal
    public InputOutcome admitted(boolean besideRunning,
                                 @Nullable RunDispatch dispatch,
                                 @Nullable Runnable discarder,
                                 @Nullable Consumer<Runnable> afterRunRegistrar) {
        InputOutcome copy = new InputOutcome(kind, commandName, commandOutput, command, prompt, agentName, modelName,
                request, hitlApplied, pendingHitl, hitlCards);
        copy.backlogPosition = backlogPosition;
        copy.rejectReason = rejectReason;
        copy.rejectDetail = rejectDetail;
        copy.besideRunning = besideRunning;
        copy.dispatch = dispatch;
        copy.discarder = discarder;
        copy.afterRunRegistrar = afterRunRegistrar;
        return copy;
    }

    /**
     * 审批决策已受理但本次未续流（携带决策写入结果与剩余未决数）
     *
     * @param hitlApplied  决策是否真的写入（false = 无匹配的挂起任务，如过期审批卡）
     * @param pendingHitl  仍待审批的挂起项数量（0 表示本批已齐备）
     */
    @Internal
    public static InputOutcome hitl(String agentName, String modelName, boolean hitlApplied, int pendingHitl) {
        return hitl(agentName, modelName, hitlApplied, pendingHitl, null);
    }

    /**
     * 审批决策已受理但本次未续流（携带剩余未决数与本批卡快照）
     *
     * @param hitlCards 本批挂起卡（含已决策项）；null 视为空集合
     */
    @Internal
    public static InputOutcome hitl(String agentName, String modelName, boolean hitlApplied, int pendingHitl,
                                    List<HitlCard> hitlCards) {
        return new InputOutcome(Kind.HITL, null, null, null, null, agentName, modelName, null,
                hitlApplied, pendingHitl, hitlCards);
    }

    /**
     * 命令在此渠道不可用（未执行），只有回执
     */
    @Internal
    public static InputOutcome unavailable(String commandName, String output, ParsedCommand command) {
        return new InputOutcome(Kind.REPLY, commandName, output, command, null, null, null, null, null, 0, null);
    }

    /**
     * 构造命令结果
     * <p>
     * {@code nested} 是命令内部声明的任务经引擎二段装配后的结果（未声明任务时为 null）。
     * kind 由它推导：有装配成果即 {@link Kind#TASK}，否则 {@link Kind#REPLY}。
     * 命令回执（{@code output}）与任务并存，由宿主自行定渲染规则。
     *
     * @param nested 二段装配结果（仅 {@link Kind#TASK} 会被采纳）
     */
    @Internal
    public static InputOutcome command(String commandName, String output,
                                      ParsedCommand command, @Nullable InputOutcome nested) {
        if (nested != null && nested.kind == Kind.TASK) {
            return new InputOutcome(Kind.TASK, commandName, output, command,
                    nested.prompt, nested.agentName, nested.modelName, nested.request, null, 0, null);
        }
        return new InputOutcome(Kind.REPLY, commandName, output, command, null, null, null, null, null, 0, null);
    }

    // ========== 查询 ==========

    /**
     * 宿主该做什么（路由只看这一个值）
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 是否为命令（含被渠道拦截、未执行的情况）
     * <p>
     * 这是「事实」而非「动作」：命令触发的任务同样是 {@link Kind#TASK}，
     * 需要区分「命令触发」与「用户手输」时读本方法或 {@link #getCommandName()}。
     */
    public boolean isCommand() {
        return commandName != null;
    }

    /**
     * 是否有可执行的任务（已装配好请求）
     */
    public boolean hasRequest() {
        return request != null;
    }

    /**
     * 是否有回执文本（命令输出，或命令不可用提示）
     */
    public boolean hasReply() {
        return commandOutput != null;
    }

    /**
     * 命令名（非命令时为 null）
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * 本次生效的技能视野（未指定、非消息路径或续流时返回空集合）
     * <p>
     * 从 Prompt attrs 派生（输入事实），供宿主在 UI 上展示「本次可见范围」，
     * 也是排查「选了没反应」的最便宜可观测点。
     */
    public List<String> getSelectedSkills() {
        return SkillTalent.getSelected(prompt);
    }

    /**
     * 本次生效的 MCP 服务视野（未指定、非消息路径或续流时返回空集合）
     */
    public List<String> getSelectedMcps() {
        return McpGatewayTalent.getSelected(prompt);
    }

    /**
     * 命令回执文本
     */
    public String getCommandOutput() {
        return commandOutput;
    }

    /**
     * 原始命令解析结果（非命令时为 null），供宿主做 rewind 等特判
     */
    public ParsedCommand getCommand() {
        return command;
    }

    /**
     * 装配好的提示词（含附件前缀与图片块；非消息输入时为 null）
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 生效的子代理名（主代理时为 null）
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 生效的模型名（未显式选择时为 null）
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 装配好的请求（宿主自行 {@code options(...)} 补充后 {@code stream()} 消费）
     * <p>
     * 无任务时为 null。
     */
    public ReActRequest getRequest() {
        return request;
    }

    /**
     * 终态收束（仅 {@link Kind#TASK} 有效）：转发 {@link #getRequest()} 的阻塞调用。
     *
     * <p>命名对齐本产品既有惯例：{@code call()} 阻塞聚合、{@link #stream()} 真流式
     * （与 {@link ReActRequest#call()} / {@link ReActRequest#stream()} 同语义）。
     * 只是一层转发，不改变 {@link Kind} 的取值，也不替宿主决定订阅时机。</p>
     *
     * <p><b>刻意不合成「回执 + 任务」流</b>（见类注释）：回执仍读 {@link #getCommandOutput()}，
     * 渲染顺序仍由宿主掌握。本方法只消除「先判 {@code getRequest() != null} 再调用」的重复样板。</p>
     *
     * @throws IllegalStateException 当前不是 {@link Kind#TASK}
     */
    public ReActResponse call() throws Throwable {
        if (kind != Kind.TASK || request == null) {
            throw new IllegalStateException("call() requires Kind.TASK, but was " + kind);
        }
        return request.call();
    }

    /**
     * 终态收束（仅 {@link Kind#TASK} 有效）：转发 {@link #getRequest()} 的响应式流。
     *
     * <p>与 {@link #call()} 同一边界；非 {@link Kind#TASK} 时返回 {@code Flux.error}
     * （响应式语义下不抛同步异常）。</p>
     */
    public Flux<AgentEvent> stream() {
        if (kind != Kind.TASK || request == null) {
            return Flux.error(new IllegalStateException("stream() requires Kind.TASK, but was " + kind));
        }
        return request.stream();
    }

    /**
     * 本次输入是否为审批决策（{@link Kind#HITL} 与审批触发的 {@link Kind#TASK} 为 true）
     */
    public boolean isHitlInput() {
        return hitlApplied != null;
    }

    /**
     * 审批决策是否真的写入
     * <p>
     * {@code false} 表示没有匹配的挂起任务（典型的过期审批卡：任务已结束，前端残留的卡仍可点击）。
     * 宿主应据此提示卡已失效而不是续流 —— 空提示词续流会回落 {@code trace.getOriginalPrompt()}，
     * 把上一轮问题原样重跑。非审批输入返回 null。
     *
     * @return true/false；本次输入不是审批决策时为 null
     */
    public Boolean isHitlApplied() {
        return hitlApplied;
    }

    /**
     * 决策写入后仍待审批的挂起项数量（本批未齐备时 {@link Kind#HITL} 携带此值）
     * <p>
     * 逐卡交互的宿主可据此决定是否展示下一张卡。非审批输入为 0。
     */
    public int getPendingHitlCount() {
        return pendingHitl;
    }

    /**
     * 是否仍有待审批的挂起卡
     */
    public boolean hasPendingHitl() {
        return pendingHitl > 0;
    }

    /**
     * 本批挂起卡（含已决策项；非审批路径为空集合，永不为 null）
     *
     * <p>与 {@link #getPendingHitlCount()} 配对：计数告诉宿主「要不要继续等」，
     * 快照告诉宿主「该问哪一张」。</p>
     */
    public List<HitlCard> getHitlCards() {
        return hitlCards;
    }

    /**
     * 下一张该处理的卡（已决策项跳过；整批已决策时回退首项；无挂起时为 null）
     *
     * <p>逐卡交互的宿主（CLI、桌面）靠它取下一张，不必自己遍历挂起任务再查决策；
     * 回退首项是为了让「残留决策」仍能透传给引擎续流——返回 null 会让任务卡死。</p>
     */
    public HitlCard getNextHitl() {
        for (HitlCard card : hitlCards) {
            if (card.isDecided() == false) {
                return card;
            }
        }

        return hitlCards.isEmpty() ? null : hitlCards.get(0);
    }

    // ========== 受理事实 ==========

    /**
     * 积压位置（仅 {@link Kind#DEFERRED}，从 1 计；其余为 0）
     *
     * @since 4.2.0
     */
    public int getBacklogPosition() {
        return backlogPosition;
    }

    /**
     * 拒绝原因（仅 {@link Kind#REJECTED}；其余为 null）。线上协议值读 {@code getRejectReason().code()}。
     *
     * @since 4.2.0
     */
    @Nullable
    public RejectReason getRejectReason() {
        return rejectReason;
    }

    /**
     * 拒绝的可展示文字（仅 {@link Kind#REJECTED}；其余为 null）
     *
     * @since 4.2.0
     */
    @Nullable
    public String getRejectDetail() {
        return rejectDetail;
    }

    /**
     * 本条是否在正在运行的任务旁边执行（忙时可执行命令，如 {@code /interrupt}、{@code /help}）。
     *
     * <p>为 true 时宿主不要改写会话的回复路由（那属于正在运行的任务），回执按本条自己的来源投递。</p>
     *
     * @since 4.2.0
     */
    public boolean isBesideRunning() {
        return besideRunning;
    }

    /**
     * 本轮是否由程序派发而来（如积压续发，且就在派发调用栈里）。
     *
     * @since 4.2.0
     */
    public boolean isDispatched() {
        return dispatch != null;
    }

    /**
     * 本轮所属的程序派发事务（非派发为 null）。宿主据此取回复路由来源（{@link RunDispatch#getOrigin()}）。
     *
     * @since 4.2.0
     */
    @Nullable
    public RunDispatch getDispatch() {
        return dispatch;
    }

    /**
     * 放弃执行（仅 {@link Kind#TASK} 有意义；幂等，其余结局为空操作）。
     *
     * <p>受理占位跟着请求走：正常订阅 {@code getRequest().stream()} 或调用 {@code call()} 时由请求自己释放，
     * 宿主<b>不需要</b>调用本方法。只有拿到 TASK 后决定不执行（开流前出错、本轮已被中断等）时调用，
     * 会话立即恢复可受理；不调用也有占位时限兜底，但期间新输入都会进积压。</p>
     *
     * @since 4.2.0
     */
    public void discard() {
        Runnable r = discarder;
        if (r != null) {
            discarder = null;
            r.run();
        }
    }

    /**
     * 登记「本轮运行结束、且会话已不再因本轮而忙」之后的回调（仅 {@link Kind#TASK} 有效）。
     *
     * <p><b>续发积压必须放在这里，不要放在宿主流的 {@code doFinally} 里。</b>Reactor 正常终止时下游的
     * {@code doFinally} 先于上游执行，而本轮的运行句柄在请求内部最上游才注销——宿主在自己的
     * {@code doFinally} 里看到的会话一定还忙，续发会被「忙」判定挡掉。本回调在注销之后执行：</p>
     * <ul>
     *     <li>{@code stream()}：每次订阅终止（完成 / 出错 / 取消）后各执行一次，晚于宿主的 {@code doFinally}
     *     （收口派发、发 done 都已完成）；</li>
     *     <li>{@code call()}：返回或抛出前执行一次。</li>
     * </ul>
     *
     * <p>须在开流 / 调用之前登记。未开流就放弃（{@link #discard()}、开流前出错）时不会执行，
     * 那是宿主自己的失败收尾。回调在执行线程上运行，异常只记日志；耗时动作宿主应自行调度。</p>
     *
     * @param hook 回调
     * @return 是否登记成功（非 TASK、或请求不是引擎装配的时为 false）
     * @since 4.2.0
     */
    public boolean afterRun(Runnable hook) {
        Consumer<Runnable> registrar = afterRunRegistrar;
        if (kind != Kind.TASK || registrar == null || hook == null) {
            return false;
        }
        registrar.accept(hook);
        return true;
    }

    @Override
    public String toString() {
        return "InputOutcome{" + kind + (commandName != null ? ", cmd=" + commandName : "")
                + (agentName != null ? ", agent=" + agentName : "")
                + (rejectReason != null ? ", reject=" + rejectReason : "")
                + (kind == Kind.DEFERRED ? ", position=" + backlogPosition : "") + "}";
    }
}
