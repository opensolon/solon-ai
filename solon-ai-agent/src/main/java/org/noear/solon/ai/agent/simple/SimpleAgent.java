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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentHandler;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Consumer;

/**
 * 简单智能体实现
 * <p>专注于单次直接响应，具备：指令增强、历史窗口管理、自动重试、JSON 格式强制约束等特性</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SimpleAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleAgent.class);
    private final SimpleAgentConfig config;

    private SimpleAgent(SimpleAgentConfig config) {
        Objects.requireNonNull(config, "Missing config!");
        this.config = config;
    }

    protected SimpleAgentConfig getConfig() {
        return config;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String title() {
        return config.getTitle();
    }

    @Override
    public String description() {
        return config.getDescription();
    }

    @Override
    public AgentProfile profile() {
        return config.getProfile();
    }


    public SimpleRequest prompt(Prompt prompt) {
        return new SimpleRequest(this, prompt);
    }

    public SimpleRequest prompt(String prompt) {
        return new SimpleRequest(this, Prompt.of(prompt));
    }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        return call(prompt, session, null);
    }

    protected AssistantMessage call(Prompt prompt, AgentSession session, ModelOptionsAmend<?, SimpleInterceptor> options) throws Throwable {
        if (ChatPrompt.isEmpty(prompt)) {
            LOG.warn("Prompt is empty!");
            return ChatMessage.ofAssistant("");
        }

        if (options == null) {
            options = config.getDefaultOptions();
        }


        FlowContext context = session.getSnapshot();

        // 初始化或恢复推理痕迹 (Trace)
        SimpleTrace trace = context.getAs(config.getTraceKey());
        if (trace == null) {
            trace = new SimpleTrace(prompt);
            context.put(config.getTraceKey(), trace);
        } else {
            trace.setPrompt(prompt);
        }

        // 1. 构建请求消息
        Prompt finalPrompt = prepareAgentPrompt(session, prompt);

        // 2. 物理调用：执行带重试机制的 LLM 请求
        AssistantMessage result = null;

        long startTime = System.currentTimeMillis();
        try {
            trace.getMetrics().setTotalDuration(0L);
            result = callWithRetry(trace, session, finalPrompt, options);
        } finally {
            trace.getMetrics().setTotalDuration(System.currentTimeMillis() - startTime);

            // 父一级团队轨迹
            TeamTrace teamTrace = TeamTrace.getCurrent(context);
            if (teamTrace != null) {
                // 汇总 token 使用情况
                teamTrace.getMetrics().addTokenUsage(trace.getMetrics().getTokenUsage());
            }
        }

        // 3. 状态回填：将输出结果自动映射到 FlowContext
        if (Assert.isNotEmpty(config.getOutputKey())) {
            context.put(config.getOutputKey(), result.getContent());
        }

        // 4. 更新会话状态与快照
        session.addHistoryMessage(config.getName(), result);
        session.updateSnapshot(context);

        return result;
    }


    /**
     * 组装完整的 Prompt 消息列表（含 SystemPrompt、OutputSchema 及历史窗口）
     */
    private Prompt prepareAgentPrompt(AgentSession session, Prompt originalPrompt) {
        String spText = config.getSystemPromptFor(session.getSnapshot());

        // 注入 JSON Schema 指令（强制格式输出）
        if (Assert.isNotEmpty(config.getOutputSchema())) {
            spText += "\n\n[IMPORTANT: OUTPUT FORMAT REQUIREMENT]\n" +
                    "Please provide the response in JSON format strictly following this schema:\n" +
                    config.getOutputSchema();
        }

        List<ChatMessage> messages = new ArrayList<>();

        if (Assert.isNotEmpty(spText)) {
            messages.add(ChatMessage.ofSystem(spText.trim()));
        }

        // 加载限定窗口大小的历史记录
        if (config.getHistoryWindowSize() > 0) {
            Collection<ChatMessage> history = session.getHistoryMessages(config.getName(), config.getHistoryWindowSize());
            if (Assert.isNotEmpty(history)) {
                messages.addAll(history);
            }
        }

        messages.addAll(originalPrompt.getMessages());


        // 消息归档：同步当前用户请求到 Session 历史
        if (!ChatPrompt.isEmpty(originalPrompt)) {
            for (ChatMessage message : originalPrompt.getMessages()) {
                session.addHistoryMessage(config.getName(), message);
            }
        }

        return Prompt.of(messages).metaPut(originalPrompt.meta());
    }

    /**
     * 实现带指数延迟的自动重试调用
     */
    private AssistantMessage callWithRetry(SimpleTrace trace, AgentSession session, Prompt finalPrompt, ModelOptionsAmend<?, SimpleInterceptor> options) throws Throwable {
        if (LOG.isTraceEnabled()) {
            LOG.trace("SimpleAgent [{}] calling model... messages: {}",
                    config.getName(),
                    ONode.serialize(finalPrompt.getMessages(), Feature.Write_PrettyFormat, Feature.Write_EnumUsingName));
        }

       ChatRequestDesc chatReq = null;

        if (config.getChatModel() != null) {
            //构建 chatModel 请求
            final TeamProtocol protocol = session.getSnapshot().getAs(Agent.KEY_PROTOCOL);
            chatReq = config.getChatModel()
                    .prompt(finalPrompt)
                    .options(o -> {
                        //配置工具
                        o.toolAdd(options.tools());

                        //协议工具
                        if (protocol != null) {
                            protocol.injectAgentTools(session.getSnapshot(), this, o::toolAdd);
                        }

                        o.toolContextPut(options.toolContext());
                        o.skillAdd(options.skills());

                        options.interceptors().forEach(item -> o.interceptorAdd(item.index, item.target));

                        if (Assert.isNotEmpty(config.getOutputSchema())) {
                            o.optionSet("response_format", Utils.asMap("type", "json_object"));
                        }

                        o.autoToolCall(options.isAutoToolCall());
                        o.optionSet(options.options());
                    });
        }


        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return doCall(trace, session, finalPrompt, chatReq);
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    throw new RuntimeException("SimpleAgent [" + name() + "] failed after " + maxRetries + " retries", e);
                }
                long delay = config.getRetryDelayMs() * (i + 1);
                LOG.warn("SimpleAgent [{}] call failed, retrying({}/{}). Error: {}", name(), i + 1, maxRetries, e.getMessage());
                Thread.sleep(delay);
            }
        }

        throw new IllegalStateException("Should not reach here");
    }

    /**
     * 执行底层物理调用，并注入 Tools, Interceptors 与 JSON 选项
     */
    private AssistantMessage doCall(SimpleTrace trace, AgentSession session, Prompt finalPrompt, ChatRequestDesc chatReq) throws Throwable {
        if (chatReq != null) {
            // chatModel 处理
            ChatResponse resp = chatReq.call();

            if (resp.getUsage() != null) {
                trace.getMetrics().addTokenUsage(resp.getUsage().totalTokens());
            }

            String clearContent = resp.hasContent() ? resp.getResultContent() : "";
            return ChatMessage.ofAssistant(clearContent);
        } else {
            // fallback 到自定义处理器
            return config.getHandler().call(finalPrompt, session);
        }
    }

    // Builder 静态方法与内部类保持不变...
    public static Builder of() {
        return new Builder();
    }

    public static Builder of(ChatModel chatModel) {
        return new Builder().chatModel(chatModel);
    }

    public static class Builder {
        private SimpleAgentConfig config = new SimpleAgentConfig();

        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        public Builder name(String name) {
            config.setName(name);
            return this;
        }

        public Builder title(String title) {
            config.setTitle(title);
            return this;
        }

        public Builder description(String description) {
            config.setDescription(description);
            return this;
        }

        public Builder profile(AgentProfile profile) {
            config.setProfile(profile);
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            config.setChatModel(chatModel);
            return this;
        }

        public Builder systemPrompt(SimpleSystemPrompt systemPrompt) {
            config.setSystemPrompt(systemPrompt);
            return this;
        }

        public Builder systemPrompt(Consumer<SimpleSystemPrompt.Builder> promptBuilder) {
            SimpleSystemPrompt.Builder builder = SimpleSystemPrompt.builder();
            promptBuilder.accept(builder);
            config.setSystemPrompt(builder.build());
            return this;
        }

        public Builder handler(AgentHandler handler) {
            config.setHandler(handler);
            return this;
        }

        public Builder modelOptions(Consumer<ModelOptionsAmend<?, SimpleInterceptor>> amendConsumer) {
            amendConsumer.accept(config.getDefaultOptions());
            return this;
        }

        public Builder retryConfig(int maxRetries, long retryDelayMs) {
            config.setRetryConfig(maxRetries, retryDelayMs);
            return this;
        }

        public Builder historyWindowSize(int historyWindowSize) {
            config.setHistoryWindowSize(historyWindowSize);
            return this;
        }

        public Builder outputKey(String val) {
            config.setOutputKey(val);
            return this;
        }

        public Builder outputSchema(String val) {
            config.setOutputSchema(val);
            return this;
        }

        public Builder outputSchema(Type type) {
            config.setOutputSchema(ToolSchemaUtil.buildOutputSchema(type));
            return this;
        }

        public Builder defaultToolAdd(FunctionTool... tools) {
            config.getDefaultOptions().toolAdd(tools);
            return this;
        }

        public Builder defaultToolAdd(Iterable<FunctionTool> tools) {
            config.getDefaultOptions().toolAdd(tools);
            return this;
        }

        public Builder defaultToolAdd(ToolProvider toolProvider) {
            config.getDefaultOptions().toolAdd(toolProvider);
            return this;
        }

        /**
         * 默认工具添加（即每次请求都会带上）
         *
         * @param toolObj 工具对象
         */
        public Builder defaultToolAdd(Object toolObj) {
            return defaultToolAdd(new MethodToolProvider(toolObj));
        }

        public Builder defaultSkillAdd(Skill... skills) {
            for (Skill skill : skills) {
                config.getDefaultOptions().skillAdd(0, skill);
            }
            return this;
        }

        public Builder defaultSkillAdd(Skill skill, int index) {
            config.getDefaultOptions().skillAdd(index, skill);
            return this;
        }

        public Builder defaultToolContextPut(String key, Object value) {
            config.getDefaultOptions().toolContextPut(key, value);
            return this;
        }

        public Builder defaultToolContextPut(Map<String, Object> objectMap) {
            config.getDefaultOptions().toolContextPut(objectMap);
            return this;
        }

        public Builder defaultInterceptorAdd(SimpleInterceptor... vals) {
            for (SimpleInterceptor val : vals) {
                config.getDefaultOptions().interceptorAdd(0, val);
            }
            return this;
        }

        public Builder defaultInterceptorAdd(int index, SimpleInterceptor val) {
            config.getDefaultOptions().interceptorAdd(index, val);
            return this;
        }

        public SimpleAgent build() {
            if (config.getHandler() == null && config.getChatModel() == null)
                throw new IllegalStateException("Handler or ChatModel must be provided");

            if (config.getName() == null) {
                config.setName("simple_agent");
            }

            if (config.getDescription() == null) {
                config.setDescription(config.getTitle() != null ? config.getTitle() : config.getName());
            }

            return new SimpleAgent(config);
        }
    }
}