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

import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;

/**
 * ReAct 智能体配置
 * * <p>定义 ReAct (Reasoning + Acting) 模式的核心参数，控制推理迭代与工具执行行为</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActAgentConfig {
    /** 智能体唯一标识名 */
    private String name = "react_agent";
    /** 链路追踪 Key (用于在 FlowContext 中存储 Trace 状态) */
    private volatile String traceKey;
    /** 智能体职责描述（用于模型识别角色任务） */
    private String role;
    /** 智能体画像 */
    private AgentProfile profile;
    /** 执行推理的基础模型 */
    private final ChatModel chatModel;
    /** 计算图微调器（自定义执行链路） */
    private Consumer<GraphSpec> graphAdjuster;
    /** 提示词模板（默认中文） */
    private ReActSystemPrompt systemPrompt = ReActSystemPromptCn.getDefault();
    /** 终止标识符（模型输出此词时停止思考循环） */
    private String finishMarker;
    /** 结果回填 Key */
    private String outputKey;

    private ReActStyle style = ReActStyle.NATIVE_TOOL;

    /** 默认运行选项（限流、重试、窗口等） */
    private final ReActOptions defaultOptions = new ReActOptions();

    /**
     * @param chatModel 执行推理的模型，不能为空
     */
    public ReActAgentConfig(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel is required");
        this.chatModel = chatModel;
    }

    // --- 配置注入 (Protected) ---

    protected void setName(String name) { this.name = name; }

    protected void setRole(String role) { this.role = role; }

    protected void setProfile(AgentProfile profile) { this.profile = profile; }

    protected void setGraphAdjuster(Consumer<GraphSpec> graphAdjuster) { this.graphAdjuster = graphAdjuster; }

    protected void setFinishMarker(String val) { this.finishMarker = val; }

    protected void setSystemPrompt(ReActSystemPrompt val) {
        this.systemPrompt = val;
    }

    protected void setOutputKey(String val) { this.outputKey = val; }

    protected void setStyle(ReActStyle style) { this.style = style; }


    // --- 参数获取 (Public) ---

    public String getName() { return name; }

    public String getTraceKey() {
        if (traceKey == null) {
            traceKey = "__" + this.name;
        }
        return traceKey;
    }

    public String getRole() { return role; }

    public AgentProfile getProfile() {
        if (profile == null) profile = new AgentProfile();
        return profile;
    }

    public ChatModel getChatModel() { return chatModel; }

    public Consumer<GraphSpec> getGraphAdjuster() { return graphAdjuster; }

    public ReActOptions getDefaultOptions() { return defaultOptions; }

    /**
     * 获取完成标记（若未设置，默认生成 [NAME_FINISH]）
     */
    public String getFinishMarker() {
        if (finishMarker == null) {
            finishMarker = "[" + name.toUpperCase() + "_FINISH]";
        }
        return finishMarker;
    }

    /**
     * 根据当前上下文获取动态渲染的系统提示词
     */
    public String getSystemPromptFor(ReActTrace trace, FlowContext context) {
        String raw = systemPrompt.getSystemPrompt(trace);
        if (context == null || raw == null) {
            return raw;
        }

        // 动态渲染模板（如解析 #{user_name}）
        String rendered = SnelUtil.render(raw, context.vars());

        return rendered;
    }

    public Locale getLocale() { return systemPrompt.getLocale(); }

    public String getOutputKey() { return outputKey; }

    public ReActStyle getStyle() {
        return style;
    }
}