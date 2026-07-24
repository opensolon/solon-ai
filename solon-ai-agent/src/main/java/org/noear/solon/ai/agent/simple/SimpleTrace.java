/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple 运行轨迹记录器 (状态机上下文)
 * <p>负责维护智能体推理过程中的短期记忆、执行路由、消息序列及上下文压缩。</p>
 *
 * @author noear
 * @since 3.8.4
 */
public class SimpleTrace implements AgentTrace {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleTrace.class);

    private transient SimpleAgentConfig config;
    private transient SimpleOptions options;
    private transient AgentSession session;
    private transient TeamProtocol protocol;

    /**
     * 智能体名字
     */
    private String agentName;

    /**
     * 运行ID
     */
    private String runId;
    /**
     * 任务提示词
     */
    private Prompt originalPrompt;
    /**
     * 工作记忆
     */
    private final Prompt workingMemory = new PromptImpl();

    private final Metrics metrics = new Metrics();

    /**
     * 任务开始时间
     */
    private long beginTimeMs;

    public SimpleTrace() {
        //反序列化用
    }

    protected void prepare(SimpleAgentConfig config, SimpleOptions options, AgentSession session, TeamProtocol protocol, String agentName) {
        this.config = config;
        this.agentName = agentName;
        this.options = options;
        this.session = session;
        this.protocol = protocol;
    }

    protected void reset(Prompt originalPrompt) {
        this.originalPrompt = originalPrompt;
        this.beginTimeMs = System.currentTimeMillis();
        this.runId = Utils.uuid();
    }

    @Override
    public String getRunId() {
        if (runId == null) {
            runId = Utils.uuid();
        }

        return runId;
    }

    @Override
    public String getAgentName() {
        return agentName;
    }

    @Override
    public Prompt getWorkingMemory() {
        return workingMemory;
    }

    @Override
    public Metrics getMetrics() {
        return metrics;
    }

    @Override
    public long getBeginTimeMs() {
        return beginTimeMs;
    }

    public SimpleAgentConfig getConfig() {
        return config;
    }

    public SimpleOptions getOptions() {
        return options;
    }

    public AgentSession getSession() {
        return session;
    }

    public TeamProtocol getProtocol() {
        return protocol;
    }

    public FlowContext getContext() {
        if (session != null) {
            return session.getContext();
        } else {
            return null;
        }
    }

    @Override
    public Prompt getOriginalPrompt() {
        return originalPrompt;
    }

    //----------------

    /**
     * 是否存在流式 sink（业务侧优先用此方法，避免直接触碰 {@link SimpleOptions#getStreamSink()}）
     */
    public boolean hasStreamSink() {
        return options != null && options.getStreamSink() != null;
    }

    /**
     * 流式订阅是否已取消（无 sink 时视为 false）
     */
    public boolean isStreamCancelled() {
        return hasStreamSink() && options.getStreamSink().isCancelled();
    }

    /**
     * 推送流块（统一安全投递：判空 / cancelled / 异常吞掉）
     */
    public void pushAgentChunk(AgentChunk chunk) {
        try {
            if (hasStreamSink() == false || isStreamCancelled()) {
                return;
            }

            options.getStreamSink().next(chunk);
        } catch (Throwable e) {
            // 忽略投递异常，避免影响主流程；debug 便于排查订阅端问题
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to push agent chunk: {}", chunk != null ? chunk.getClass().getSimpleName() : null, e);
            }
        }
    }

    /**
     * 直接推送流块（调用方已完成 cancelled 判定；仅吞掉投递异常）
     */
    public void pushAgentChunkDo(AgentChunk chunk) {
        try {
            options.getStreamSink().next(chunk);
        } catch (Throwable e) {
            // 忽略投递异常，避免影响主流程；debug 便于排查订阅端问题
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to push agent chunk: {}", chunk != null ? chunk.getClass().getSimpleName() : null, e);
            }
        }
    }
}
