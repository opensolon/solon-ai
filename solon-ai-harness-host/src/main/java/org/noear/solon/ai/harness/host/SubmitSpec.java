/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.function.Consumer;

/**
 * 一次提交的描述（{@link HarnessHost#submit(String, String, SubmitSpec)} / {@link HarnessHost#stream}）。
 *
 * <pre>{@code
 * host.submit(tenant, sid, SubmitSpec.of(text)
 *         .origin(InputOrigin.of("im", userId, chatId, msgId))
 *         .principal(tenant + "/" + userId)     // 校验会话主体，防止用别人的 sessionId 提交
 *         .whenBusy(SubmitSpec.BusyPolicy.STEER) // 忙时先插话，插不进再排队
 *         .customize(in -> in.selectedModel("gpt-4o").attr("effort", "high")));
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class SubmitSpec {
    /**
     * 会话忙（正在运行 / 受理窗口 / 有积压）时怎么处理这条输入。忙时可执行的命令、待审批的会话不受影响
     * （前者照常执行，后者由引擎给出 {@code HITL_PENDING}）。
     */
    public enum BusyPolicy {
        /** 进积压，当前一轮结束后自动续发（缺省；推送型宿主用） */
        QUEUE,
        /**
         * 先作为插话交给正在运行的那一轮（需装配 steer 插件；斜杠命令、带附件的输入不插话），插不进再排队
         * （IM / 聊天输入框「边跑边补充」用）
         */
        STEER,
        /** 直接拒绝（{@code BUSY}），不进积压（一问一答型宿主用：请求结束了，排队的那一轮没人接） */
        REJECT
    }

    private final String text;
    private InputOrigin origin = InputOrigin.NONE;
    private String principal;
    private BusyPolicy whenBusy = BusyPolicy.QUEUE;
    private Consumer<HarnessInput> customizer;
    private RunSink observer;

    private SubmitSpec(@Nullable String text) {
        this.text = text;
    }

    /** 用户输入文本（含斜杠命令；可空，只带附件时由 {@link #customize} 补充） */
    public static SubmitSpec of(@Nullable String text) {
        return new SubmitSpec(text);
    }

    /** 来源（回复路由；缺省 {@code InputOrigin.NONE}） */
    public SubmitSpec origin(@Nullable InputOrigin origin) {
        this.origin = origin == null ? InputOrigin.NONE : origin;
        return this;
    }

    /**
     * 提交者的主体（登录身份）：非空时必须与会话主体一致，否则拒绝（{@code SCOPE_UNAVAILABLE}）。
     *
     * <p>多用户 SaaS 应当总是传：sessionId 来自客户端，不校验就能用别人的会话。SERVER 模式下不传只告警。</p>
     */
    public SubmitSpec principal(@Nullable String principal) {
        this.principal = principal;
        return this;
    }

    /** 会话忙时的处理方式（缺省 {@link BusyPolicy#QUEUE}；null 视为缺省） */
    public SubmitSpec whenBusy(@Nullable BusyPolicy whenBusy) {
        this.whenBusy = whenBusy == null ? BusyPolicy.QUEUE : whenBusy;
        return this;
    }

    /** 补充输入参数（附件、显式模型 / 子代理、宿主域属性等） */
    public SubmitSpec customize(@Nullable Consumer<HarnessInput> customizer) {
        this.customizer = customizer;
        return this;
    }

    /**
     * 只作用于本次请求开出的那一轮的附加监听（在全局 sink 之后调用）。输入进了积压时不会被调用。
     */
    public SubmitSpec observe(@Nullable RunSink observer) {
        this.observer = observer;
        return this;
    }

    @Nullable
    public String getText() {
        return text;
    }

    public InputOrigin getOrigin() {
        return origin;
    }

    @Nullable
    public String getPrincipal() {
        return principal;
    }

    public BusyPolicy getWhenBusy() {
        return whenBusy;
    }

    @Nullable
    Consumer<HarnessInput> getCustomizer() {
        return customizer;
    }

    @Nullable
    RunSink getObserver() {
        return observer;
    }
}
