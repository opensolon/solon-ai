/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.*;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.*;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct (Reason + Act) 协同推理智能体
 *
 * <p>该智能体实现了经典 ReAct 推理模式：通过【思考(Thought) -> 动作(Act) -> 观察(Observation)】的循环，
 * 使 LLM 能够使用外部工具解决复杂任务。其核心是一个基于 Solon Flow 构建的计算图。</p>
 *
 * <p>执行流程：</p>
 * <pre>
 * Start -> [Plan (可选)] -> [Reason (决策)] --(Action意图)--> [Action (执行工具)] --(结果回传)--> Reason
 * |
 * (结束意图) --> End
 * </pre>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActAgent implements Agent<ReActRequest, ReActResponse> {
    public final static String ID_REASON = "reason";
    public final static String ID_ACTION = "action";

    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private final ReActAgentConfig config;

    private final ReasonTask reasonTask;
    private final ActionTask actionTask;

    public ReActAgent(ReActAgentConfig config) {
        Objects.requireNonNull(config, "Missing config!");
        this.config = config;

        reasonTask = new ReasonTask(config, this);
        actionTask = new ActionTask(config);
    }

    public ReActAgentConfig getConfig() {
        return config;
    }

    public ChatModel getModel() {
        return config.getDefaultOptions().getChatModel();
    }

    /**
     * 获取会话中的执行轨迹
     */
    public @Nullable ReActTrace getTrace(AgentSession session) {
        return session.getContext().getAs(config.getTraceKey());
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String role() {
        if (config.getRole() == null) {
            return config.getName();
        }

        return config.getRole();
    }

    @Override
    public AgentProfile profile() {
        return config.getProfile();
    }

    private ReActRequest promptDo(Prompt prompt) {
        return new ReActRequest(this, prompt);
    }

    @Override
    public ReActRequest prompt(Prompt prompt) {
        return promptDo(prompt);
    }

    @Override
    public ReActRequest prompt(String prompt) {
        if (prompt == null) {
            return promptDo(null);
        } else {
            return promptDo(Prompt.of(prompt));
        }
    }

    @Override
    public ReActRequest prompt() {
        return promptDo(null);
    }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        return this.call(prompt, session, null);
    }

    protected ReActTrace getTrace(FlowContext context) {
        ReActTrace trace = context.getAs(config.getTraceKey());
        if (trace == null) {
            trace = new ReActTrace();
            context.put(config.getTraceKey(), trace);
        }
        return trace;
    }

    /**
     * 智能体核心调用流程：管理会话上下文、痕迹记录与拦截器触发
     */
    protected AssistantMessage call(Prompt prompt, AgentSession session, ReActOptions options) throws Throwable {
        final FlowContext context = session.getContext();
        final TeamProtocol protocol = context.getAs(Agent.KEY_PROTOCOL);
        final TeamTrace parentTeamTrace = TeamTrace.getCurrent(context);

        // 初始化或恢复推理痕迹 (Trace)
        final ReActTrace trace = getTrace(context);

        if (options == null) {
            options = config.getDefaultOptions().copy();
        }

        if (parentTeamTrace != null) {
            //传递流控
            options.setStreamSink(parentTeamTrace.getOptions().getStreamSink());
        }

        //添加必要的工具上下文
        options.getToolContext().put(ChatSession.ATTR_SESSIONID, session.getSessionId());

        trace.prepare(config, options, session, protocol, config.getName());


        if (protocol != null) {
            protocol.injectAgentTools(session.getContext(), this, trace::addProtocolTool);
        }


        if (Prompt.isEmpty(prompt)) {
            // 可能是恢复执行（之前中断的）
            prompt = trace.getOriginalPrompt();

            if (Prompt.isEmpty(prompt)) {
                LOG.warn("Prompt is empty!");
                return ChatMessage.ofAssistant("");
            }
        } else {
            // 新任务（重置相关数据）
            trace.reset(prompt);
            prompt.attrs().computeIfAbsent(ChatSession.ATTR_SESSIONID, (k) -> session.getSessionId());

            // 1. 加载历史上下文（短期记忆）
            if (trace.getWorkingMemory().isEmpty() && options.getSessionWindowSize() > 0) {
                if (parentTeamTrace == null) {
                    Collection<ChatMessage> history = session.getLatestMessages(options.getSessionWindowSize());
                    for (ChatMessage message : history) {
                        message.addMetadata(AgentTrace.META_FIRST, 1); //初心
                        trace.getWorkingMemory().addMessage(message);
                    }
                }
            }

            //新的问题
            for (ChatMessage message : prompt.getMessages()) {
                message.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
                if (parentTeamTrace == null) {
                    session.addMessage(message);
                }

                message.addMetadata(AgentTrace.META_FIRST, 1); //初心
                trace.getWorkingMemory().addMessage(message);
            }

            //更新下快照（记录上面的数据）
            session.updateSnapshot();
        }

        //添加计划模式（要在激活才能之前）
        if (trace.getOptions().isPlanningMode()) {
            trace.getOptions().getModelOptions().talentAdd(new PlanTalent(trace));
        }

        //如果提示词没问题，开始激活才能
        trace.activeTalents();

        //添加模式工具
        if (trace.getOptions().isFeedbackMode()) {
            trace.getOptions().getModelOptions().toolAdd(FeedbackTool.getTool(
                    trace.getOptions().getFeedbackDescription(trace),
                    trace.getOptions().getFeedbackReasonDescription(trace)));
        }


        // 拦截器：任务开始事件
        for (RankEntity<ReActInterceptor> item : options.getInterceptors()) {
            if (item.target.isEnabled()) {
                item.target.onAgentStart(trace);
            }
        }

        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new RunStartChunk(trace));
        }

        if (trace.getSession().isPending() == false) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ReActAgent [{}] start thinking... Prompt: {}", config.getName(), prompt.getUserContent());
            }

            long startTime = System.currentTimeMillis();
            try {
                trace.getMetrics().reset();

                // 核心执行：基于计算图进行循环推理
                context.with(KEY_CURRENT_UNIT_TRACE_KEY, config.getTraceKey(), () -> {
                    evalDo(trace, context);
                });
            } finally {
                // 记录性能指标
                long duration = System.currentTimeMillis() - startTime;
                trace.getMetrics().setTotalDuration(duration);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("ReActAgent [{}] finished. Duration: {}ms, Turns: {}, Tools: {}",
                            config.getName(), duration, trace.getTurnCount(), trace.getToolCallCount());
                }

                // 父一级团队轨迹
                if (parentTeamTrace != null) {
                    // 汇总 token 使用情况
                    parentTeamTrace.getMetrics().addMetrics(trace.getMetrics());
                }
            }
        } else {
            //如果有挂起
            if (trace.getFinalAnswer() == null) {
                trace.setFinalAnswer(session.getPendingReason());
            }
        }

        String result = trace.getFinalAnswer();

        // 结果回填
        if (Assert.isNotEmpty(config.getOutputKey())) {
            context.put(config.getOutputKey(), result);
        }

        // 终态保留 lastReason 的 media（生图 / media-only），避免 finalAnswer 纯字符串把图丢掉
        AssistantMessage assistantMessage = buildFinalAssistantMessage(result, trace.getLastReasonMessage());
        assistantMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());

        // media-only 时 result 可能为空，仍需落 Session / WorkingMemory
        if (Assert.isNotEmpty(result) || assistantMessage.hasMedia()) {
            if (parentTeamTrace == null) {
                session.addMessage(assistantMessage);
            }
            trace.getWorkingMemory().addMessage(assistantMessage);
        }

        session.updateSnapshot();

        if (trace.isAbnormal()) {
            if (trace.hasStreamSink()) {
                trace.pushAgentChunk(new ReasonDeltaChunk(trace, null, assistantMessage));

                //@deprecated 4.0.4
                trace.pushAgentChunk(new ReasonChunk(trace, null, assistantMessage));
            }
        }

        // 拦截器：任务结束事件
        for (RankEntity<ReActInterceptor> item : options.getInterceptors()) {
            if (item.target.isEnabled()) {
                item.target.onAgentEnd(trace);
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("ReActAgent [{}] finished, abnormal:{}, finalAnswer: {}", config.getName(), trace.isAbnormal(), assistantMessage.getContent());
        }

        return assistantMessage;
    }

    /**
     * 构造终态 AssistantMessage：文本用 finalAnswer；若 lastReason 带 media 则一并保留。
     */
    private AssistantMessage buildFinalAssistantMessage(String result, AssistantMessage lastReason) {
        String text = result == null ? "" : result;
        if (lastReason != null && lastReason.hasMedia()) {
            List<ContentBlock> mediaBlocks = new ArrayList<>();
            for (ContentBlock block : lastReason.getBlocks()) {
                if (!(block instanceof TextBlock)) {
                    mediaBlocks.add(block);
                }
            }
            if (!mediaBlocks.isEmpty()) {
                return ChatMessage.ofAssistant(text, mediaBlocks);
            }
        }
        return ChatMessage.ofAssistant(text);
    }

    /**
     * ReAct 调度执行
     */
    private void evalDo(ReActTrace trace, FlowContext context) throws Throwable {
        if (Assert.isEmpty(trace.getRoute())) {
            trace.setRoute(ReActAgent.ID_REASON);
        }


        while (true) {
            if (trace.getSession().isPending()) {
                break;
            }

            if (ReActAgent.ID_REASON.equals(trace.getRoute())) {
                reasonTask.run(trace, context);
            } else if (ReActAgent.ID_ACTION.equals(trace.getRoute())) {
                actionTask.run(trace, context);
            } else {
                break;
            }
        }
    }

    /// //////////// Builder 模式 ////////////

    public static Builder of(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {
        private final ReActAgentConfig config;

        public Builder(ChatModel chatModel) {
            this.config = new ReActAgentConfig(chatModel);
        }

        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        public Builder style(ReActStyle style) {
            config.setStyle(style);
            return this;
        }

        public Builder name(String name) {
            config.setName(name);
            return this;
        }

        public Builder role(String role) {
            config.setRole(role);
            return this;
        }

        public Builder profile(AgentProfile profile) {
            config.setProfile(profile);
            return this;
        }

        public Builder profile(Consumer<AgentProfile> profileConsumer) {
            profileConsumer.accept(config.getProfile());
            return this;
        }

        public Builder instruction(String instruction) {
            config.setSystemPrompt(ReActSystemPrompt.builder().instruction(instruction).build());
            return this;
        }

        public Builder instruction(Function<ReActTrace, String> instruction) {
            config.setSystemPrompt(ReActSystemPrompt.builder().instruction(instruction).build());
            return this;
        }

        public Builder systemPrompt(AgentSystemPrompt<ReActTrace> val) {
            config.setSystemPrompt(val);
            return this;
        }

        /**
         * 定义 LLM 输出中的任务结束标识符
         */
        public Builder finishMarker(String val) {
            config.setFinishMarker(val);
            return this;
        }

        public Builder modelOptions(Consumer<ModelOptionsAmend<?, ReActInterceptor>> chatOptions) {
            chatOptions.accept(config.getDefaultOptions().getModelOptions());
            return this;
        }

        /**
         * @since 4.0.4
         */
        public Builder attr(String name, Object val) {
            config.getDefaultOptions().setAttr(name, val);
            return this;
        }

        /**
         * @since 4.0.4
         */
        public Builder attrs(Map<String, Object> vals) {
            if(Utils.isNotEmpty(vals)) {
                config.getDefaultOptions().getAttrs().putAll(vals);
            }
            return this;
        }

        public Builder retryConfig(int maxRetries) {
            config.getDefaultOptions().setRetryConfig(maxRetries);
            return this;
        }

        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.getDefaultOptions().setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        /**
         * 单次任务允许的最大推理回合数（防止死循环）
         */
        public Builder maxTurns(int val) {
            config.getDefaultOptions().setMaxTurns(val);
            return this;
        }

        public Builder autoRethink(boolean val) {
            config.getDefaultOptions().setAutoRethink(val);
            return this;
        }

        public Builder outputKey(String val) {
            config.setOutputKey(val);
            return this;
        }

        public Builder outputSchema(String val) {
            config.getDefaultOptions().setOutputSchema(val);
            return this;
        }

        public Builder outputSchema(Type type) {
            config.getDefaultOptions().setOutputSchema(ToolSchemaUtil.buildOutputSchema(type));
            return this;
        }

        public Builder sessionWindowSize(int val) {
            config.getDefaultOptions().setSessionWindowSize(val);
            return this;
        }


        public Builder defaultTalentAdd(Talent... talents) {
            config.getDefaultOptions().getModelOptions().talentAdd(talents);
            return this;
        }

        public Builder defaultTalentAdd(Talent talent, int index) {
            config.getDefaultOptions().getModelOptions().talentAdd(index, talent);
            return this;
        }

        public Builder defaultToolAdd(FunctionTool... tools) {
            config.getDefaultOptions().getModelOptions().toolAdd(tools);
            return this;
        }

        public Builder defaultToolAdd(Collection<FunctionTool> tools) {
            config.getDefaultOptions().getModelOptions().toolAdd(tools);
            return this;
        }

        public Builder defaultToolAdd(ToolProvider toolProvider) {
            config.getDefaultOptions().getModelOptions().toolAdd(toolProvider);
            return this;
        }


        public Builder defaultToolContextPut(String key, Object value) {
            config.getDefaultOptions().getModelOptions().toolContextPut(key, value);
            return this;
        }

        public Builder defaultToolContextPut(Map<String, Object> objectsMap) {
            config.getDefaultOptions().getModelOptions().toolContextPut(objectsMap);
            return this;
        }

        public Builder defaultInterceptorAdd(ReActInterceptor... vals) {
            for (ReActInterceptor val : vals) {
                config.getDefaultOptions().getModelOptions().interceptorAdd(0, val);
            }

            return this;
        }

        public Builder defaultInterceptorAdd(int index, ReActInterceptor val) {
            config.getDefaultOptions().getModelOptions().interceptorAdd(index, val);
            return this;
        }

        /**
         * 规划模式（推理前先制定计划）
         */
        public Builder planningMode(boolean val) {
            config.getDefaultOptions().setPlanningMode(val);
            return this;
        }


        public Builder planningInstruction(Function<ReActTrace, String> provider) {
            config.getDefaultOptions().setPlanningInstructionProvider(provider);
            return this;
        }

        public Builder planningInstruction(String instruction) {
            config.getDefaultOptions().setPlanningInstructionProvider(t -> instruction);
            return this;
        }

        /**
         * 反馈模式（允许主动寻求外部帮助/反馈）
         */
        public Builder feedbackMode(boolean val) {
            config.getDefaultOptions().setFeedbackMode(val);
            return this;
        }

        public Builder feedbackDescription(String description) {
            config.getDefaultOptions().setFeedbackDescriptionProvider(t -> description);
            return this;
        }

        public Builder feedbackDescription(Function<ReActTrace, String> provider) {
            config.getDefaultOptions().setFeedbackDescriptionProvider(provider);
            return this;
        }

        public Builder feedbackReasonDescription(String description) {
            config.getDefaultOptions().setFeedbackReasonDescriptionProvider(t -> description);
            return this;
        }

        public Builder feedbackReasonDescription(Function<ReActTrace, String> provider) {
            config.getDefaultOptions().setFeedbackReasonDescriptionProvider(provider);
            return this;
        }

        public ReActAgent build() {
            if (config.getName() == null) {
                config.setName("react_agent");
            }

            return new ReActAgent(config);
        }
    }
}