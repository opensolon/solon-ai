/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 开流之前的准备视图（{@link RunSink#prepare}）：在执行线程上、{@code stream()} 之前，宿主可以补充请求
 * （注入拦截器、模型参数、cwd 等）或给提示词加属性。
 *
 * <p>只暴露「改请求」需要的东西：开流、放弃、续发都由框架掌握，宿主拿不到。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class RunPrep {
    private final HostRun run;
    private final HarnessEngine engine;
    private final ReActRequest request;
    private final Prompt prompt;
    private final String modelName;

    RunPrep(HostRun run, HarnessEngine engine, ReActRequest request, @Nullable Prompt prompt, @Nullable String modelName) {
        this.run = run;
        this.engine = engine;
        this.request = request;
        this.prompt = prompt;
        this.modelName = modelName;
    }

    public HostRun run() {
        return run;
    }

    public AgentSession session() {
        return run.getSession();
    }

    /** 本租户的引擎（读配置、取模型等） */
    public HarnessEngine engine() {
        return engine;
    }

    /** 装配好的请求：用 {@code request().options(o -> ...)} 补充 */
    public ReActRequest request() {
        return request;
    }

    /** 装配好的提示词（续流等非消息路径为 null） */
    @Nullable
    public Prompt prompt() {
        return prompt;
    }

    /** 生效的模型名（未显式选择为 null） */
    @Nullable
    public String modelName() {
        return modelName;
    }

    /** 生效的子代理名（主代理为 null） */
    @Nullable
    public String agentName() {
        return run.getAgentName();
    }
}
