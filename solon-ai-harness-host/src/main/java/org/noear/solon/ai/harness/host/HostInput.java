/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.ai.harness.host.SubmitResult.Kind;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次输入的只读描述（宿主视角），随 {@link HostSink} 的会话级回调一起交给宿主。
 *
 * <p>回调据此知道「发给谁」（{@link #getTenant()} + {@link #getSessionId()} + {@link #getOrigin()}），
 * 以及「这是什么输入、引擎怎么处理的」（文本、命令、结局、是否在运行中的任务旁边执行、是否为队列续发）。</p>
 *
 * <p>与相邻类型的分工：</p>
 * <ul>
 *   <li>{@link HarnessInput}：交给引擎的输入，可改（调用方交给 {@code submit} 的那个对象 / {@code Builder#input}；框架交给引擎的是它的副本）；</li>
 *   <li>{@code HostInput}：框架回报给宿主的那次输入，只读，多了受理结果；</li>
 *   <li>{@link HostRun}：一次输入开出的那一轮运行（0 或 1 个），经 {@link HostRun#getInput()} 指回本对象。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HostInput {
    private final String tenant;
    private final String sessionId;
    private final InputOrigin origin;
    private final AgentSession session;
    private final String text;
    private final Kind kind;
    private final String commandName;
    private final ParsedCommand command;
    private final boolean besideRunning;
    private final boolean hasReply;
    private final QueueItem queueItem;
    private final String selectedModel;
    private final String selectedAgent;
    private final Map<String, String> attrs;
    private final boolean resume;

    private HostInput(String tenant, String sessionId, InputOrigin origin, AgentSession session, String text,
                      Kind kind, String commandName, ParsedCommand command, boolean besideRunning,
                      boolean hasReply, QueueItem queueItem, HarnessInput in) {
        this.tenant = tenant;
        this.sessionId = sessionId;
        this.origin = origin == null ? InputOrigin.NONE : origin;
        this.session = session;
        this.text = text;
        this.kind = kind;
        this.commandName = commandName;
        this.command = command;
        this.besideRunning = besideRunning;
        this.hasReply = hasReply;
        this.queueItem = queueItem;
        this.selectedModel = in == null ? null : in.getSelectedModel();
        this.selectedAgent = in == null ? null : in.getSelectedAgent();
        this.attrs = in == null || in.getAttrs().isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(in.getAttrs()));
        this.resume = in != null && (in.isResume() || in.isHitlDecision());
    }

    /** 交给引擎之前就有了结果的输入（会话不存在、非持有节点 → REJECTED；忙时插话 → STEERED） */
    static HostInput early(Kind kind, String tenant, String sessionId, InputOrigin origin,
                           @Nullable AgentSession session, @Nullable String text, @Nullable HarnessInput in) {
        return new HostInput(tenant, sessionId, origin, session, text, kind, null, null, false, false, null, in);
    }

    /**
     * 引擎已给出结局的输入。
     *
     * @param kind 框架换算后的结果类型（与同一次 {@code submit} 返回的 {@link SubmitResult#getKind()} 一致）
     */
    static HostInput of(Kind kind, String tenant, AgentSession session, InputOrigin origin, @Nullable String text,
                        InputOutcome outcome, @Nullable QueueItem queueItem, @Nullable HarnessInput in) {
        return new HostInput(tenant, session.getSessionId(), origin, session, text, kind,
                outcome.getCommandName(), outcome.getCommand(), outcome.isBesideRunning(), outcome.hasReply(), queueItem,
                in);
    }

    /** 引擎键（多租户时为租户标识） */
    public String getTenant() {
        return tenant;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * 回复路由：本次输入的来源；队列续发时为当初排队那条消息的来源。永不为 null。
     */
    public InputOrigin getOrigin() {
        return origin;
    }

    /** 会话（会话不存在、非持有节点时为 null） */
    @Nullable
    public AgentSession getSession() {
        return session;
    }

    /** 输入文本（审批决策等无文本输入为 null） */
    @Nullable
    public String getText() {
        return text;
    }

    /**
     * 这次输入的结果类型，永不为 null。
     *
     * <p>与同一次提交里 {@code submit} 返回的 {@link SubmitResult#getKind()} 一致：
     * 开了流为 {@code STARTED}；会话不存在、非持有节点、忙时可执行命令触发了任务都是 {@code REJECTED}；
     * 插话成功为 {@code STEERED}。队列续发、后台轮次开出的一轮也是 {@code STARTED}。</p>
     */
    public Kind getKind() {
        return kind;
    }

    /** 命令名（非命令为 null） */
    @Nullable
    public String getCommandName() {
        return commandName;
    }

    /** 命令解析结果（非命令为 null） */
    @Nullable
    public ParsedCommand getCommand() {
        return command;
    }

    public boolean isCommand() {
        return commandName != null;
    }

    /**
     * 是否在正在运行的任务旁边执行（忙时可执行命令）。为 true 时不要改写会话的回复路由（那属于正在运行的任务）。
     */
    public boolean isBesideRunning() {
        return besideRunning;
    }

    /** 引擎是否给出了回执文本（命令无输出时为 false：宿主可在 {@link HostSink#admitted} 里兜底提示） */
    public boolean hasReply() {
        return hasReply;
    }

    /**
     * 是否是「续跑」：审批决策或显式续流，开出的那一轮接着挂起的那一轮跑。
     * 界面据此决定续用原来的输出流（序号、回放缓冲），而不是新开一段。
     */
    public boolean isResume() {
        return resume;
    }

    /** 是否由队列续发而来 */
    public boolean isDispatched() {
        return queueItem != null;
    }

    /** 队列续发的那一项（非续发为 null） */
    @Nullable
    public QueueItem getQueueItem() {
        return queueItem;
    }

    /**
     * 本次输入显式选择的模型（未选择为 null；队列续发时为排队时的选择）。
     *
     * <p>与 {@link HostRun#getModelName()}（引擎解析后的生效值）不同：这是「用户这次选了什么」，
     * 宿主在 {@link HostSink#admitted} 里据此持久化会话级选择。</p>
     */
    @Nullable
    public String getSelectedModel() {
        return selectedModel;
    }

    /** 本次输入显式选择的子代理（未选择为 null；队列续发时为排队时的选择） */
    @Nullable
    public String getSelectedAgent() {
        return selectedAgent;
    }

    /**
     * 本次输入的宿主属性（{@code HarnessInput.attr(k, v)} / Builder#input 写入的；
     * 队列续发时为排队时保存的属性）。只读，永不为 null。
     */
    public Map<String, String> getAttrs() {
        return attrs;
    }

    /** 取一个宿主属性（不存在为 null） */
    @Nullable
    public String getAttr(String key) {
        return attrs.get(key);
    }

    @Override
    public String toString() {
        return "HostInput{" + tenant + "/" + sessionId
                + ", " + kind
                + (commandName != null ? ", cmd=" + commandName : "")
                + (queueItem != null ? ", dispatched" : "") + "}";
    }
}
