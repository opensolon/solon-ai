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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Nullable;
import reactor.core.publisher.Flux;

/**
 * 一次输入的处置结果
 * <p>
 * {@link org.noear.solon.ai.harness.HarnessEngine#handle} 的返回值：把「命令识别 / 子代理解析 /
 * 附件装配 / Prompt 构建 / 请求装配」的结果一次性交给宿主。
 *
 * <p>宿主有两种取用方式：
 * <ul>
 *   <li>要「决策」的，读 {@link #getKind()}：一个值对应一个动作，一次 switch 即可分完；</li>
 *   <li>要「结果」的，直接 {@link #stream()}，它把已发生的事忠实映射成一条事件流。</li>
 * </ul>
 * 两者的差别只有一点：{@link #stream()} 不再暴露 kind，因此表达不出
 * 「HITL 决策已写入、续流时机由谁决定」这类需要宿主二次决策的差异。</p>
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
         * HITL 审批决策已应用，宿主决定是否恢复流
         * <p>
         * 注意：{@link #stream()} 对本类型返回空流（决策已写入，但续流时机由宿主决定），
         * 续流请显式调用 {@code engine.handle(HarnessInput.of(session, null).resume(true)...)}。
         */
        HITL,
        /**
         * 输入为空（无文本也无附件），无需处理
         */
        IGNORED
    }

    private final Kind kind;
    private final AgentSession session;
    private final String commandName;
    private final String commandOutput;
    private final ParsedCommand command;
    private final Prompt prompt;
    private final String agentName;
    private final String modelName;
    private final ReActRequest request;

    private InputOutcome(Kind kind, AgentSession session, String commandName, String commandOutput,
                        ParsedCommand command, Prompt prompt, String agentName, String modelName,
                        ReActRequest request) {
        this.kind = kind;
        this.session = session;
        this.commandName = commandName;
        this.commandOutput = commandOutput;
        this.command = command;
        this.prompt = prompt;
        this.agentName = agentName;
        this.modelName = modelName;
        this.request = request;
    }

    // ========== 工厂 ==========

    /**
     * 有可执行的任务（普通消息或续流）：宿主执行装配好的请求
     */
    public static InputOutcome task(Prompt prompt, String agentName, String modelName, ReActRequest request) {
        return new InputOutcome(Kind.TASK, null, null, null, null, prompt, agentName, modelName, request);
    }

    public static InputOutcome ignored() {
        return new InputOutcome(Kind.IGNORED, null, null, null, null, null, null, null, null);
    }

    public static InputOutcome hitl(String agentName, String modelName) {
        return new InputOutcome(Kind.HITL, null, null, null, null, null, agentName, modelName, null);
    }

    /**
     * 命令在此渠道不可用（未执行），只有回执
     *
     * @param session 会话（{@link #stream()} 需要它来构造回执事件；可空，此时 stream() 为空流）
     */
    public static InputOutcome unavailable(AgentSession session, String commandName, String output, ParsedCommand command) {
        return new InputOutcome(Kind.REPLY, session, commandName, output, command,
                null, null, null, null);
    }

    /**
     * 构造命令结果
     * <p>
     * {@code nested} 是命令内部声明的任务经引擎二段装配后的结果（未声明任务时为 null）。
     * kind 由它推导：有装配成果即 {@link Kind#TASK}，否则 {@link Kind#REPLY}。
     * 命令回执（{@code output}）与任务并存，由宿主自行定渲染规则。
     *
     * @param session 会话（{@link #stream()} 需要它来构造回执事件；可空，此时回执不进流）
     * @param nested  二段装配结果（仅 {@link Kind#TASK} 会被采纳）
     */
    public static InputOutcome command(AgentSession session, String commandName, String output,
                                      ParsedCommand command, @Nullable InputOutcome nested) {
        if (nested != null && nested.kind == Kind.TASK) {
            return new InputOutcome(Kind.TASK, session, commandName, output, command,
                    nested.prompt, nested.agentName, nested.modelName, nested.request);
        }
        return new InputOutcome(Kind.REPLY, session, commandName, output, command,
                null, null, null, null);
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
     * 装配好的请求（可直接 {@code stream()} 消费，也可继续追加 options）
     * <p>
     * 无任务时为 null。
     */
    public ReActRequest getRequest() {
        return request;
    }

    /**
     * 把本次输入的处置结果忠实映射成一条事件流
     * <p>
     * 只搬运「已经发生的事」，不做任何决策：
     * <ul>
     *   <li>{@link Kind#TASK} → 回执（若有，{@link InputTextEvent}）后接请求的流
     *       （冷流，无请求时只有回执）。命令触发的任务因此「回执 + 任务」是同一条流，
     *       宿主无需分两段消费</li>
     *   <li>{@link Kind#REPLY} → 回执事件（回执为空时为空流）</li>
     *   <li>{@link Kind#HITL} / {@link Kind#IGNORED} → 空流</li>
     * </ul>
     *
     * <p>两点刻意不做：</p>
     * <ol>
     *   <li><b>不切换调度器。</b>与 {@code getRequest().stream()} 保持同构 —— 何时订阅、在哪个线程订阅
     *       由宿主决定（Web 端必须先注册 Disposable 再订阅，否则忙碌判定会出现空闲窗口）。</li>
     *   <li><b>不对 HITL 自动续流。</b>三端续流时机各不相同（Web 要等整批审批完成、桌面续流要重挂
     *       HITL 拦截器、CLI 是多轮循环），引擎替任何一端做选择都是错的。审批决策已由
     *       {@code handle()} 写入，续流请显式调用 {@code engine.handle(...resume(true)...)}。</li>
     * </ol>
     */
    public Flux<AgentEvent> stream() {
        switch (kind) {
            case TASK: {
                if (hasReply() == false) {
                    // 无回执时直接返回请求自身的流（保持与 getRequest().stream() 同构）
                    return request == null ? Flux.empty() : request.stream();
                }
                Flux<AgentEvent> receipt = Flux.just(new InputTextEvent(session, commandOutput));
                return request == null ? receipt : Flux.concat(receipt, request.stream());
            }
            case REPLY:
                if (hasReply() == false) {
                    return Flux.empty();
                }
                return Flux.just(new InputTextEvent(session, commandOutput));
            case HITL:
            case IGNORED:
            default:
                return Flux.empty();
        }
    }

    @Override
    public String toString() {
        return "InputOutcome{" + kind + (commandName != null ? ", cmd=" + commandName : "")
                + (agentName != null ? ", agent=" + agentName : "") + "}";
    }
}
