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
 *         .model("gpt-4o")
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
    private final AgentSession session;
    private final String text;

    private InputAttachments attachments;
    private String modelName;
    private String selectedAgent;
    private String source;
    private String sourceUserId;
    private String replyTarget;
    private String messageId;
    private String hitlAction;
    private String hitlCallId;
    private String hitlOutput;

    private Predicate<String> agentNameValidator;
    private boolean trimContent;
    private String explicitAgent;
    private boolean commandAware = true;
    private boolean mentionAware = true;
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
    public String getModelName() {
        return modelName;
    }

    /**
     * 选择器传入的子代理名（仅当输入未显式提及时作为兜底）
     */
    public String getSelectedAgent() {
        return selectedAgent;
    }

    public String getSource() {
        return source;
    }

    public String getSourceUserId() {
        return sourceUserId;
    }

    public String getReplyTarget() {
        return replyTarget;
    }

    public String getMessageId() {
        return messageId;
    }

    /**
     * HITL 决策动作（approve / reject），为空表示本次输入不是审批
     */
    public String getHitlAction() {
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

    public boolean isHitlDecision() {
        return hitlAction != null && !hitlAction.isEmpty();
    }

    // ========== 链式设置 ==========

    public HarnessInput model(@Nullable String modelName) {
        this.modelName = modelName;
        return this;
    }

    public HarnessInput agent(@Nullable String selectedAgent) {
        this.selectedAgent = selectedAgent;
        return this;
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
     * 回复路由（IM 等远程渠道使用）
     */
    public HarnessInput route(@Nullable String source, @Nullable String sourceUserId,
                              @Nullable String replyTarget, @Nullable String messageId) {
        this.source = source;
        this.sourceUserId = sourceUserId;
        this.replyTarget = replyTarget;
        this.messageId = messageId;
        return this;
    }

    /**
     * HITL 审批决策
     *
     * @param action approve / reject
     * @param callId 目标任务标识（可为空）
     */
    public HarnessInput hitl(@Nullable String action, @Nullable String callId) {
        this.hitlAction = action;
        this.hitlCallId = callId;
        return this;
    }

    /**
     * HITL 决策附带的工具返回值改写文本（可为空，为空时按原决策执行）
     *
     * @see #hitl(String, String)
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
     * 有效性由调用方先行校验；此处只负责生效与剥离。
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
     * 置为 false 时原文原样保留，子代理只取显式指定值与选择器传入值。适用于「文本由程序生成」
     * 的路径（如 goal objective）：即使以 {@code @xxx} 开头，也不应被当作子代理提及剥离，
     * 而应作为普通提示词交给代理自己识别。
     */
    public HarnessInput mentionAware(boolean mentionAware) {
        this.mentionAware = mentionAware;
        return this;
    }

    public boolean isMentionAware() {
        return mentionAware;
    }

    /**
     * 续流模式（默认 false）：本次不产生新用户输入，仅按会话中挂起的 tool_calls 继续执行
     * <p>
     * 文本与附件均被忽略，用于 HITL 决策后的续轮 —— 决策已由 {@link #hitl(String, String)}
     * 写入，续轮不需要正文。
     */
    public HarnessInput resume(boolean resume) {
        this.resume = resume;
        return this;
    }

    public boolean isResume() {
        return resume;
    }
}
