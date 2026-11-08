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
 *         .steerWhenBusy(true)                  // 忙时先插话，插不进再排队
 *         .customize(in -> in.selectedModel("gpt-4o").attr("effort", "high")));
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class SubmitSpec {
    private final String text;
    private InputOrigin origin = InputOrigin.NONE;
    private String principal;
    private boolean steerWhenBusy;
    private boolean rejectWhenBusy;
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

    /**
     * 会话正在运行时，先把这条输入作为插话交给运行中的那一轮（需装配 steer 插件）；插不进再走正常受理
     * （排队或拒绝）。斜杠命令不插话。缺省 false。
     */
    public SubmitSpec steerWhenBusy(boolean steerWhenBusy) {
        this.steerWhenBusy = steerWhenBusy;
        return this;
    }

    /**
     * 会话忙时直接拒绝（{@code BUSY}），不进积压：一问一答型宿主用（请求结束了，排队的那一轮没人接）。
     * 忙时可执行的命令照常执行。与 {@link #steerWhenBusy} 可同时开：先插话，插不进再拒绝。缺省 false。
     */
    public SubmitSpec rejectWhenBusy(boolean rejectWhenBusy) {
        this.rejectWhenBusy = rejectWhenBusy;
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

    public boolean isSteerWhenBusy() {
        return steerWhenBusy;
    }

    public boolean isRejectWhenBusy() {
        return rejectWhenBusy;
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
