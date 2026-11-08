/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 一轮运行（run）的只读描述，随轮次级回调（{@link HostSink#open} / {@link RunSink}）一起交给宿主。
 *
 * <p>与 {@link HostInput} 的分工：一次输入开出 0 或 1 轮运行。输入是「用户这次输入了什么」（文本、命令、来源、
 * 队列项、选择），受理那一刻就定下来；运行只放「跑起来以后才知道」的信息（生效的子代理与模型、是否后台轮次、轮次 id）。
 * 输入相关的一律经 {@link #getInput()} 取。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HostRun {
    private static final AtomicLong SEQ = new AtomicLong();

    private final long id = SEQ.incrementAndGet();
    private final HostInput input;
    private final AgentSession session;
    private final String agentName;
    private final String modelName;
    private final String programChannel;
    private final long createdAt = System.currentTimeMillis();

    HostRun(HostInput input, AgentSession session, @Nullable String agentName, @Nullable String modelName,
            @Nullable String programChannel) {
        this.input = input;
        this.session = session;
        this.agentName = agentName;
        this.modelName = modelName;
        this.programChannel = programChannel;
    }

    /** 轮次 id（进程内递增，日志与排查用；不持久化） */
    public long getId() {
        return id;
    }

    /** 发起本轮的那次输入（来源、文本、命令、队列项、用户选择都在这里） */
    public HostInput getInput() {
        return input;
    }

    /** 引擎键（多租户时为租户标识） */
    public String getTenant() {
        return input.getTenant();
    }

    public String getSessionId() {
        return input.getSessionId();
    }

    public AgentSession getSession() {
        return session;
    }

    /** 生效的子代理名（主代理为 null） */
    @Nullable
    public String getAgentName() {
        return agentName;
    }

    /** 生效的模型名（未显式选择为 null） */
    @Nullable
    public String getModelName() {
        return modelName;
    }

    /** 是否为 goal / loop 的后台轮次 */
    public boolean isProgram() {
        return programChannel != null;
    }

    /** 后台轮次的渠道名（{@code goal} / {@code loop}）；用户输入为 null */
    @Nullable
    public String getProgramChannel() {
        return programChannel;
    }

    /** 本轮创建时刻（毫秒） */
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return getTenant() + "/" + getSessionId() + "#" + id
                + (agentName != null ? "(agent=" + agentName + ")" : "")
                + (input.isDispatched() ? "(dispatched)" : "")
                + (programChannel != null ? "(program=" + programChannel + ")" : "");
    }
}
