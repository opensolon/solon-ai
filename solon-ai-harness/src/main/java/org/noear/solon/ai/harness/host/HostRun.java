/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 一条运行流（run）的只读描述，随 {@link HostSink} 的运行类回调一起传给宿主。
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HostRun {
    private final String tenant;
    private final String sessionId;
    private final InputOrigin origin;
    private final String agentName;
    private final boolean dispatched;
    private final String programChannel;
    private volatile String resultText;

    HostRun(String tenant, String sessionId, InputOrigin origin, @Nullable String agentName,
            boolean dispatched, @Nullable String programChannel) {
        this.tenant = tenant;
        this.sessionId = sessionId;
        this.origin = origin == null ? InputOrigin.NONE : origin;
        this.agentName = agentName;
        this.dispatched = dispatched;
        this.programChannel = programChannel;
    }

    /** 引擎键（多租户时为租户标识） */
    public String getTenant() {
        return tenant;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * 回复路由：用户输入时为本次输入的来源；队列续发时为当初排队那条消息的来源；
     * goal / loop 后台轮次为 {@code InputOrigin.channel("goal" / "loop")}。永不为 null。
     */
    public InputOrigin getOrigin() {
        return origin;
    }

    /** 生效的子代理名（主代理为 null） */
    @Nullable
    public String getAgentName() {
        return agentName;
    }

    /** 是否由队列续发而来 */
    public boolean isDispatched() {
        return dispatched;
    }

    /** 是否为 goal / loop 的后台轮次（这类轮次同步执行，不推送流事件，最终文本见 {@link #getResultText()}） */
    public boolean isProgram() {
        return programChannel != null;
    }

    /** 后台轮次的渠道名（{@code goal} / {@code loop}）；用户输入为 null */
    @Nullable
    public String getProgramChannel() {
        return programChannel;
    }

    /** 后台轮次的最终文本（仅 {@link #isProgram()} 且成功时，在 {@code runFinished} 时可读） */
    @Nullable
    public String getResultText() {
        return resultText;
    }

    void setResultText(String resultText) {
        this.resultText = resultText;
    }

    @Override
    public String toString() {
        return "HostRun{" + tenant + "/" + sessionId
                + (agentName != null ? ", agent=" + agentName : "")
                + (dispatched ? ", dispatched" : "")
                + (programChannel != null ? ", program=" + programChannel : "") + "}";
    }
}
