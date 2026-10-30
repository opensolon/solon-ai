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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.chat.content.MediaBlock;
import org.noear.solon.lang.Nullable;

import java.util.function.Predicate;

/**
 * 一次输入的完整上下文
 * <p>
 * 这是「完整输入体验」的专用参数对象：宿主持有原始输入与端特性（模型选择、附件、回复路由），
 * 引擎据此完成命令识别、子代理解析、附件装配与 Prompt 构建。
 *
 * <p>示例：</p>
 * <pre>{@code
 * InputAttachments atts = InputAttachments.of()
 *         .media(ImageBlock.ofBase64(base64, "image/png").filePath(".uploads/a.png"))
 *         .reference(".uploads/report.pdf");
 *
 * InputOutcome outcome = engine.handle(HarnessInput.of(session, "帮我看看这个 @coder")
 *         .selectedModel("gpt-4o")
 *         .attachments(atts));
 *
 * switch (outcome.getKind()) {
 *     case TASK:   // 有任务：先渲染回执（若有），再执行
 *         if (outcome.hasReply()) {
 *             render(outcome.getCommandOutput());
 *         }
 *         outcome.getRequest().stream().subscribe(event -> render(event));
 *         break;
 *     case REPLY:  // 只有回执（命令已执行，或命令在本渠道不可用）
 *         render(outcome.getCommandOutput());
 *         break;
 *     default:     // HITL / IGNORED
 *         break;
 * }
 * }</pre>
 *
 * @author noear
 * @since 4.2
 */
public final class HarnessInput {
    /** 目标会话；为空时请求装配走临时会话（HITL 决策写入等会话侧操作为空操作） */
    private final AgentSession session;
    /** 原始输入文本：命令识别、提及解析与 Prompt 构建的数据源；HITL 决策与续流路径不消费 */
    private final String text;

    /** 附件装配结果，永不为 null（构造器初始化，attachments(null) 不覆盖）；有附件时不作命令处理 */
    private InputAttachments attachments;
    /** 显式指定的模型名；为空时回退会话级选择，再回退默认模型（resolveSessionModelName） */
    private String selectedModel;
    /**
     * 选择器传入的子代理名，优先级随路径不同：HITL 决策与续流路径最高
     * （resolveHitlAgent / handle 第 2 步），消息路径仅作提及未命中时的兜底。
     */
    private String selectedAgent;
    /** 命令上下文工厂；为空时 handle 内部落为 BufferedCommandContextFactory（回执缓冲进 commandOutput） */
    private CommandContextFactory commandContextFactory;
    /** 渠道来源身份；消费于命令分发（回执路由）与 Prompt 的 source 元数据；null 归一化为 {@link InputOrigin#NONE} */
    private InputOrigin origin = InputOrigin.NONE;
    /** HITL 决策动作：非空即进入决策分支（优先于空输入判空）；线上字符串到枚举的解析由宿主完成（{@link HITLAction#of}） */
    private HITLAction hitlAction;
    /** HITL 决策目标任务标识；非空时按 callId 精确取挂起任务，为空时取当前第一个挂起任务 */
    private String hitlCallId;
    /** 工具返回值改写文本；非空时作为决策备注写入，由拦截器覆写工具返回（见 {@link #getHitlOutput()}） */
    private String hitlOutput;

    /** 子代理名格式校验；对提及名与 selectedAgent 兜底名都生效，不通过或表中不存在均判无效 */
    private Predicate<String> agentNameValidator;
    /** 剥离提及后是否去掉剩余文本首尾空白；注意即使未命中提及，只要本值为 true 也会 trim 全文 */
    private boolean trimContent;
    /**
     * 请求体显式指定的子代理，优先级高于输入里的提及；引擎不再查子代理表
     * （有效性由宿主先行校验），仅剥离同名前缀；续流路径中作为 selectedAgent 缺席时的回退。
     */
    private String explicitAgent;
    /** 是否识别命令前缀（默认 true）；有附件时无论本值如何都不作命令处理 */
    private boolean commandAware = true;
    /** 是否解析输入开头的 {@code @} 提及（默认 true）；false 时原文保留，只认 explicitAgent 与 selectedAgent */
    private boolean agentAware = true;
    /** 渠道是否具备终端交互能力（默认 false）；经分发器成为 CommandContext.isTerminal()，控制 terminalOnly 命令 */
    private boolean terminal;
    /** 续流模式：不产生新输入，仅按会话挂起的 tool_calls 续跑；文本与附件均被忽略 */
    private boolean resume;

    private HarnessInput(AgentSession session, String text) {
        this.session = session;
        this.text = text;
        this.attachments = InputAttachments.of();
    }

    /**
     * 创建输入上下文
     *
     * @param session 会话（可为空，由请求使用临时会话）
     * @param text    原始输入（可包含命令前缀与子代理提及前缀）
     */
    public static HarnessInput of(@Nullable AgentSession session, @Nullable String text) {
        return new HarnessInput(session, text);
    }

    // ========== 读取 ==========

    public AgentSession getSession() {
        return session;
    }

    /**
     * 原始输入
     */
    public String getText() {
        return text;
    }

    /**
     * 附件装配结果（多媒体项 + 文件引用），永不为空对象
     */
    public InputAttachments getAttachments() {
        return attachments;
    }

    public boolean hasAttachments() {
        return attachments.hasAny();
    }

    /**
     * 显式指定的模型名（为空时回退会话级选择，再回退默认模型）
     */
    public String getSelectedModel() {
        return selectedModel;
    }

    /**
     * 选择器传入的子代理名（仅当输入未显式提及时作为兜底）
     */
    public String getSelectedAgent() {
        return selectedAgent;
    }

    /**
     * 本次输入的渠道来源身份（永不返回 null，无来源时为 {@link InputOrigin#NONE}）
     */
    public InputOrigin getOrigin() {
        return origin;
    }

    /**
     * HITL 决策动作，为空表示本次输入不是审批
     */
    public HITLAction getHitlAction() {
        return hitlAction;
    }

    /**
     * HITL 决策目标的任务标识（可为空，为空时回退到当前挂起任务）
     */
    public String getHitlCallId() {
        return hitlCallId;
    }

    /**
     * HITL 决策附带的工具返回值改写文本（可为空）
     * <p>
     * 桌面端「批准并改写工具结果」用它：非空时作为决策备注写入，
     * 由 HITLInterceptor 覆写工具返回（见 {@code HITLDecision.getComment()}）。
     */
    public String getHitlOutput() {
        return hitlOutput;
    }

    /** 决策动作非空即本次输入是审批（枚举时代空字符串不存在，无需 isEmpty 判断） */
    public boolean isHitlDecision() {
        return hitlAction != null;
    }

    // ========== 链式设置 ==========

    public HarnessInput selectedModel(@Nullable String selectedModel) {
        this.selectedModel = selectedModel;
        return this;
    }

    /**
     * 本次请求生效的子代理（宿主已解析的结果，优先于输入里的 {@code @} 提及）。
     * <p>
     * 与 {@link #explicitAgent} 的区别：本方法传入已是「生效名」（含 {@code @} 解析结果），
     * 引擎会再次尝试从正文剥离同名前缀；{@code explicitAgent} 表示宿主已自行剥离正文，
     * 引擎不再改动正文。
     */
    public HarnessInput selectedAgent(@Nullable String selectedAgent) {
        this.selectedAgent = selectedAgent;
        return this;
    }

    /**
     * 命令上下文工厂（由宿主实现）。为空时使用缓冲式默认实现
     * {@link BufferedCommandContextFactory}。
     * <p>
     * 与 {@link #commandAware} 配套：前者决定「是否识别命令」，本方法决定
     * 「识别到命令后回执怎么落地」。两者都为空/默认时，宿主无需关心命令体系。
     */
    public HarnessInput commandContextFactory(@Nullable CommandContextFactory commandContextFactory) {
        this.commandContextFactory = commandContextFactory;
        return this;
    }

    /**
     * 命令上下文工厂；为空表示使用缓冲式默认实现
     */
    public CommandContextFactory getCommandContextFactory() {
        return commandContextFactory;
    }

    /**
     * 附件装配结果（由宿主落盘并构造：多媒体项 + 文件引用）
     * <p>
     * 落盘与路径校验由宿主负责（各端规则不同），引擎只负责提示词装配。
     *
     * @see InputAttachments
     */
    public HarnessInput attachments(@Nullable InputAttachments attachments) {
        if (attachments != null) {
            this.attachments = attachments;
        }
        return this;
    }

    /**
     * 追加一个多媒体块（已落盘时请先把相对路径写到 {@code block.filePath(...)}）
     *
     * @see InputAttachments#media(MediaBlock)
     */
    public HarnessInput media(MediaBlock block) {
        attachments.media(block);
        return this;
    }

    /**
     * 追加一个文件引用（工作区相对路径，以 {@code [附件: path]} 告知模型）
     *
     * @see InputAttachments#reference(String)
     */
    public HarnessInput reference(String path) {
        attachments.reference(path);
        return this;
    }

    /**
     * 渠道来源身份（IM 等远程渠道使用）
     */
    public HarnessInput origin(@Nullable InputOrigin origin) {
        this.origin = origin == null ? InputOrigin.NONE : origin;
        return this;
    }

    /**
     * HITL 审批决策
     * <p>
     * 线上协议是字符串（WS 报文 / HTTP 参数），解析由宿主在交给引擎前完成：
     * {@code .hitl(HITLAction.of(action), callId)}，脏值在边界处显式失败。
     *
     * @param action 决策动作（approve / reject / skip）
     * @param callId 目标任务标识（可为空）
     * @see HITLAction#of(String)
     */
    public HarnessInput hitl(@Nullable HITLAction action, @Nullable String callId) {
        this.hitlAction = action;
        this.hitlCallId = callId;
        return this;
    }

    /**
     * HITL 决策附带的工具返回值改写文本（可为空，为空时按原决策执行）
     *
     * @see #hitl(HITLAction, String)
     */
    public HarnessInput hitlOutput(@Nullable String hitlOutput) {
        this.hitlOutput = hitlOutput;
        return this;
    }

    /**
     * 子代理名格式校验（如宿主端的字符集/长度限制），为空时只按子代理表判断
     */
    public HarnessInput agentNameValidator(@Nullable Predicate<String> validator) {
        this.agentNameValidator = validator;
        return this;
    }

    public Predicate<String> getAgentNameValidator() {
        return agentNameValidator;
    }

    /**
     * 剥离提及后是否去掉首尾空白（桌面端需要，CLI/Web 保持既有口径）
     */
    public HarnessInput trimContent(boolean trimContent) {
        this.trimContent = trimContent;
        return this;
    }

    public boolean isTrimContent() {
        return trimContent;
    }

    /**
     * 请求体显式指定的子代理（优先级高于输入里的提及）。
     * <p>
     * 与 {@link #selectedAgent} 的区别：本方法表示正文已由宿主自行剥离（引擎不再解析 {@code @} 提及），
     * 仅用作生效子代理；有效性由调用方先行校验。
     */
    public HarnessInput explicitAgent(@Nullable String explicitAgent) {
        this.explicitAgent = explicitAgent;
        return this;
    }

    public String getExplicitAgent() {
        return explicitAgent;
    }

    /**
     * 是否把输入当作「可能以命令前缀开头」来识别（默认 true）
     * <p>
     * 置为 false 时引擎跳过命令分发，输入原样按消息处理。适用于「文本来源不是用户手输」的路径
     * （如定时任务提示词、已受理的排队输入）：这些文本即使以命令前缀开头，也不应被当作命令执行。
     */
    public HarnessInput commandAware(boolean commandAware) {
        this.commandAware = commandAware;
        return this;
    }

    public boolean isCommandAware() {
        return commandAware;
    }

    /**
     * 是否解析输入开头的子代理提及（默认 true）
     * <p>
     * 与 {@link #commandAware} 成对：`{@code /}` 前缀表示命令，`{@code @}` 前缀表示子代理。
     * 置为 false 时原文原样保留，子代理只取显式指定值与选择器传入值。适用于「文本由程序生成」
     * 的路径（如 goal objective）：即使以 {@code @xxx} 开头，也不应被当作子代理提及剥离，
     * 而应作为普通提示词交给代理自己识别。
     */
    public HarnessInput agentAware(boolean agentAware) {
        this.agentAware = agentAware;
        return this;
    }

    public boolean isAgentAware() {
        return agentAware;
    }

    /**
     * 本次输入所在渠道是否具备命令行终端交互能力（默认 false）
     * <p>
     * 决定 {@link org.noear.solon.ai.harness.command.Command#terminalOnly()} 的命令能否可见、可执行：
     * 为 false 时分发器直接拦截并回执统一提示，{@code /help} 也不列出这类命令。
     * CLI 宿主置 true；Web/IM/桌面等缓冲式宿主保持默认。
     * <p>
     * 注意这与 {@link #origin} 无关：桌面端是本地进程但无终端能力，CLI 有渠道标识且具备终端能力。
     * 引擎读取本值后经分发器交给 {@link org.noear.solon.ai.harness.command.CommandContext#isTerminal()}，
     * 保证「拦截判据」与「上下文自述」同源。
     */
    public HarnessInput terminal(boolean terminal) {
        this.terminal = terminal;
        return this;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 续流模式（默认 false）：本次不产生新用户输入，仅按会话中挂起的 tool_calls 继续执行
     * <p>
     * 文本与附件均被忽略。两种用法：
     * <ul>
     * <li>与 {@link #hitl(HITLAction, String)} 合用 —— 决策写入后<b>尝试</b>续流，
     * 是否真的续流由引擎自检决定：本批挂起项已齐备时返回 {@link InputOutcome.Kind#TASK}，
     * 否则返回 {@link InputOutcome.Kind#HITL}（剩余未决数读 {@code getPendingHitlCount()}）。
     * 批挂起时宿主应逐张提问，直到拿到 {@code TASK}；</li>
     * <li>单独使用 —— 不写决策，直接按挂起状态续跑（如宿主已批量写完全部决策后统一恢复）。</li>
     * </ul>
     * <p>
     * 注意：{@code resume} 只表达宿主意愿，不保证续流一定发生。判定条件见
     * {@link InputOutcome.Kind#HITL} 的三条说明（决策未命中、本批未齐备、未传 resume）。
     */
    public HarnessInput resume(boolean resume) {
        this.resume = resume;
        return this;
    }

    public boolean isResume() {
        return resume;
    }
}
