/*
 * Copyright 2017-2026 noear.org and authors
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

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSystemPrompt;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ReAct 智能体运行选项
 * <p>用于动态控制推理过程中的深度、重试策略及拦截行为</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActOptions implements NonSerializable {
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();

    /** 智能体职责描述（用于模型识别角色） */
    private String role;
    /** 智能体指令描述（用于模型识别指令） */
    private String instruction;
    /** 提示词模板（默认中文） */
    private AgentSystemPrompt<ReActTrace> systemPrompt = ReActSystemPromptCn.getDefault();
    /** 语言环境 */
    private Locale locale;

    /**
     * @since 4.0.4
     */
    public Map<String, Object> getAttrs() {
        return attrs;
    }

    public Object getAttr(String key) {
        return attrs.get(key);
    }

    public <T> T getAttrAs(String key) {
        return (T) attrs.get(key);
    }

    public void setAttr(String key, Object val) {
        attrs.put(key, val);
    }

    //------------

    private transient FluxSink<AgentEvent> streamSink;

    /**
     * 执行推理的基础模型
     */
    private ChatModel chatModel;

    /**
     * 工具调用上下文（透传给 FunctionTool）
     */
    private final ModelOptionsAmend<?, ReActInterceptor> modelOptions = new ModelOptionsAmend<>();

    private String talentInstruction;

    /**
     * 最大推理回合数（防止死循环）
     */
    private int maxTurns = 8; //初始 maxTurns
    private int maxTurnsNew = 0; //被改过后的 maxTurns

    /**
     * 自动反思
     */
    private boolean autoRethink = false;

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试延迟基础时间（毫秒）
     */
    private long retryDelayMs = 1000L;
    /**
     * 会话回溯窗口大小
     */
    private int sessionWindowSize = 8;
    /**
     * 输出格式约束 (JSON Schema)
     */
    private String outputSchema;

    /**
     * 反馈模式（允许主动寻求外部帮助/反馈）
     */
    private boolean feedbackMode = false;
    private Function<ReActTrace, String> feedbackDescriptionProvider;
    private Function<ReActTrace, String> feedbackReasonDescriptionProvider;
    /**
     * 规划模式（推理前先制定计划）
     */
    private boolean planningMode = false;
    private Function<ReActTrace, String> planningInstructionProvider;


    public ReActOptions(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    private ReActOptions() {

    }

    /**
     * 浅拷贝选项实例
     */
    protected ReActOptions copy() {
        ReActOptions tmp = new ReActOptions();
        tmp.attrs.putAll(this.attrs);
        tmp.modelOptions.putAll(modelOptions);

        tmp.role = role;
        tmp.instruction = instruction;
        tmp.systemPrompt = systemPrompt;
        tmp.locale = locale;

        tmp.chatModel = chatModel;
        tmp.maxTurns = maxTurns;
        tmp.autoRethink = autoRethink;
        tmp.maxRetries = maxRetries;
        tmp.retryDelayMs = retryDelayMs;
        tmp.sessionWindowSize = sessionWindowSize;
        tmp.outputSchema = outputSchema;

        tmp.feedbackMode = feedbackMode;
        tmp.feedbackDescriptionProvider = feedbackDescriptionProvider;
        tmp.feedbackReasonDescriptionProvider = feedbackReasonDescriptionProvider;
        tmp.planningMode = planningMode;
        tmp.planningInstructionProvider = planningInstructionProvider;
        //tmp.talentInstruction = talentInstruction;
        // streamSink 为运行时注入字段，不参与配置拷贝

        return tmp;
    }

    protected void setStreamSink(FluxSink<AgentEvent> streamSink) {
        this.streamSink = streamSink;
    }

    public FluxSink<AgentEvent> getStreamSink() {
        return streamSink;
    }


    // --- 配置注入 (Protected) ---

    protected void setRole(String role) { this.role = role; }

    protected void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    protected void setSystemPrompt(AgentSystemPrompt<ReActTrace> val) {
        this.systemPrompt = val;
    }

    protected void setLocale(Locale locale) {
        this.locale = locale;
    }

    protected void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置容错策略
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
    }

    /**
     * 设置容错策略
     */
    protected void setRetryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
    }

    /**
     * 设置会话回溯深度
     */
    protected void setSessionWindowSize(int sessionWindowSize) {
        this.sessionWindowSize = Math.max(0, sessionWindowSize);
    }

    protected void setMaxTurns(int val) {
        this.maxTurns = val;
    }

    public void addMaxTurns(int val) {
        if (maxTurnsNew > 0) {
            this.maxTurnsNew += val;
        } else {
            this.maxTurnsNew = maxTurns + val;
        }
    }

    /**
     * 自动反思
     */
    protected void setAutoRethink(boolean autoRethink) {
        this.autoRethink = autoRethink;
    }

    protected void setOutputSchema(String val) {
        this.outputSchema = val;
    }


    protected void setTalentInstruction(String talentInstruction) {
        this.talentInstruction = talentInstruction;
    }

    protected void setPlanningMode(boolean planningMode) {
        this.planningMode = planningMode;
    }

    protected void setPlanningInstructionProvider(Function<ReActTrace, String> provider) {
        this.planningInstructionProvider = provider;
    }

    protected void setFeedbackMode(boolean feedbackMode) {
        this.feedbackMode = feedbackMode;
    }

    protected void setFeedbackDescriptionProvider(Function<ReActTrace, String> provider) {
        this.feedbackDescriptionProvider = provider;
    }

    protected void setFeedbackReasonDescriptionProvider(Function<ReActTrace, String> provider) {
        this.feedbackReasonDescriptionProvider = provider;
    }

    // --- 参数获取 (Public) ---

    public String getRole() { return role; }

    public String getInstruction() {
        return instruction;
    }

    /**
     * 根据当前上下文获取动态渲染的系统提示词
     */
    public String getSystemPromptFor(ReActTrace trace, FlowContext context) {
        String prompt = systemPrompt.getSystemPrompt(trace);
        if (prompt == null) {
            return prompt;
        }

        if (context != null) {
            // 动态渲染模板（如解析 #{user_name}）
            prompt = SnelUtil.render(prompt, context.vars());
        }

        // Talent 级指令（增加一个子标题，强化感知）
        if (trace.getOptions().getTalentInstruction() != null) {
            //sb.append("\n## 补充业务准则\n");
            prompt = prompt + "\n" + trace.getOptions().getTalentInstruction() + "\n";
        }

        return prompt;
    }

    public Locale getLocale() {
        if (locale == null) {
            return systemPrompt.getLocale();
        } else {
            return locale;
        }
    }

    public FunctionTool getTool(String name) {
        return modelOptions.tool(name);
    }

    public Collection<FunctionTool> getTools() {
        return modelOptions.tools();
    }

    public Map<String, Object> getToolContext() {
        return modelOptions.toolContext();
    }

    public CacheControl getCacheControl() {
        return modelOptions.cacheControl();
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public ModelOptionsAmend<?, ReActInterceptor> getModelOptions() {
        return modelOptions;
    }

    public String getTalentInstruction() {
        return talentInstruction;
    }

    public Collection<RankEntity<ReActInterceptor>> getInterceptors() {
        return modelOptions.interceptors();
    }

    /**
     * 初始 maxTurns 值
     */
    public int getInitialMaxTurns() {
        return maxTurns;
    }

    public int getMaxTurns() {
        if (maxTurnsNew > 0) {
            return maxTurnsNew;
        } else {
            return maxTurns;
        }
    }

    /**
     * @deprecated 4.0 Use {@link #getInitialMaxTurns()} instead.
     */
    @Deprecated
    public int getInitialMaxSteps() {
        return getInitialMaxTurns();
    }

    /**
     * @deprecated 4.0 Use {@link #getMaxTurns()} instead.
     */
    @Deprecated
    public int getMaxSteps() {
        return getMaxTurns();
    }

    public boolean isAutoRethink() {
        return autoRethink;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public int getSessionWindowSize() {
        return sessionWindowSize;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public boolean isPlanningMode() {
        return planningMode;
    }

    public String getPlanningInstruction(ReActTrace trace) {
        if (planningInstructionProvider != null) {
            return planningInstructionProvider.apply(trace);
        } else {
            return null;
        }
    }


    public boolean isFeedbackMode() {
        return feedbackMode;
    }

    public String getFeedbackDescription(ReActTrace trace) {
        if (feedbackDescriptionProvider == null) {
            return null;
        }

        return feedbackDescriptionProvider.apply(trace);
    }

    public String getFeedbackReasonDescription(ReActTrace trace) {
        if (feedbackReasonDescriptionProvider == null) {
            return null;
        }

        return feedbackReasonDescriptionProvider.apply(trace);
    }
}