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

import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Nullable;

/**
 * 一次输入的处置结果
 * <p>
 * {@link org.noear.solon.ai.harness.HarnessEngine#handle} 的返回值：把「命令识别 / 子代理解析 /
 * 附件装配 / Prompt 构建 / 请求装配」的结果一次性交给宿主。
 *
 * <p>宿主只有一种取用方式：读 {@link #getKind()} 做一次 switch —— 一个值对应一个动作。
 * 需要执行任务时，流来自 {@code getRequest().stream()}（宿主自己决定订阅时机），
 * 回执来自 {@link #getCommandOutput()}（宿主自己决定渲染顺序）。</p>
 *
 * <p><b>刻意不提供「回执 + 任务合成一条流」的便捷方法。</b>真实宿主对回执与任务的顺序要求各不相同：
 * CLI 由命令即时写终端、Web 要求「流门复位早于回执」、桌面要求序号回放与 HITL 拦截器重挂、
 * ACP 需要 {@code takeWhile} 取消判定。任何替宿主拼好的流都得被拆开重拼，
 * 反而遮住了这些必须由宿主掌握的时序。</p>
 *
 * <p>命名沿用本产品既有的 {@code *Outcome} 家族（{@code RunOutcome}、{@code TurnOutcome}、
 * {@code PollOutcome}）：都是「一次处理后归入几类结局」的同一类问题。{@code TASK} 表示
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
     * 四个值互斥且穷尽：每个值都是「本次输入已经发生的事」的一个真实结局，
     * 新增能力（如命令权限门控）不会新增值，只会落到既有结局上。
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
        IGNORED
    }

    /** 宿主动作路由值（四值互斥穷尽，语义见 {@link Kind}） */
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

    private InputOutcome(Kind kind, String commandName, String commandOutput,
                        ParsedCommand command, Prompt prompt, String agentName, String modelName,
                        ReActRequest request, Boolean hitlApplied, int pendingHitl) {
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
    }

    // ========== 工厂 ==========

    /**
     * 有可执行的任务（普通消息或续流）：宿主执行装配好的请求
     */
    public static InputOutcome task(Prompt prompt, String agentName, String modelName, ReActRequest request) {
        return new InputOutcome(Kind.TASK, null, null, null, prompt, agentName, modelName, request, null, 0);
    }

    /**
     * 有可执行的任务，且本次输入是审批决策（携带决策写入结果）
     * <p>
     * 能续流即意味着本批已齐备，故 {@code pendingHitl} 恒为 0（非“未统计”）。
     */
    public static InputOutcome task(Prompt prompt, String agentName, String modelName, ReActRequest request,
                                   boolean hitlApplied) {
        return new InputOutcome(Kind.TASK, null, null, null, prompt, agentName, modelName, request,
                hitlApplied, 0);
    }

    public static InputOutcome ignored() {
        return new InputOutcome(Kind.IGNORED, null, null, null, null, null, null, null, null, 0);
    }

    /**
     * 审批决策已受理但本次未续流（携带决策写入结果与剩余未决数）
     *
     * @param hitlApplied  决策是否真的写入（false = 无匹配的挂起任务，如过期审批卡）
     * @param pendingHitl  仍待审批的挂起项数量（0 表示本批已齐备）
     */
    public static InputOutcome hitl(String agentName, String modelName, boolean hitlApplied, int pendingHitl) {
        return new InputOutcome(Kind.HITL, null, null, null, null, agentName, modelName, null,
                hitlApplied, pendingHitl);
    }

    /**
     * 命令在此渠道不可用（未执行），只有回执
     */
    public static InputOutcome unavailable(String commandName, String output, ParsedCommand command) {
        return new InputOutcome(Kind.REPLY, commandName, output, command, null, null, null, null, null, 0);
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
    public static InputOutcome command(String commandName, String output,
                                      ParsedCommand command, @Nullable InputOutcome nested) {
        if (nested != null && nested.kind == Kind.TASK) {
            return new InputOutcome(Kind.TASK, commandName, output, command,
                    nested.prompt, nested.agentName, nested.modelName, nested.request, null, 0);
        }
        return new InputOutcome(Kind.REPLY, commandName, output, command, null, null, null, null, null, 0);
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

    @Override
    public String toString() {
        return "InputOutcome{" + kind + (commandName != null ? ", cmd=" + commandName : "")
                + (agentName != null ? ", agent=" + agentName : "") + "}";
    }
}
