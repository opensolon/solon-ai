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
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentHandler;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Consumer;

/**
 * 简单智能体实现
 * <p>专注于单次直接响应，具备：指令增强、历史窗口管理、自动重试、JSON 格式强制约束等特性</p>
 *
 * @author noear 2026/1/12 created
 */
public class SimpleAgent implements Agent {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleAgent.class);
    private final SimpleAgentConfig config;

    private SimpleAgent(SimpleAgentConfig config) {
        Objects.requireNonNull(config, "Missing config!");
        this.config = config;
    }

    @Override
    public String name() { return config.getName(); }

    @Override
    public String title() { return config.getTitle(); }

    @Override
    public String description() { return config.getDescription(); }

    @Override
    public AgentProfile profile() { return config.getProfile(); }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        FlowContext context = session.getSnapshot();

        // 1. 消息归档：同步当前用户请求到 Session 历史
        if (!Prompt.isEmpty(prompt)) {
            for (ChatMessage message : prompt.getMessages()) {
                session.addHistoryMessage(config.getName(), message);
            }
        }

        // 2. 物理调用：执行带重试机制的 LLM 请求
        List<ChatMessage> messages = buildMessages(session, prompt);
        AssistantMessage assistantMessage = callWithRetry(session, messages);

        // 3. 结果处理：剥离思考过程，仅保留纯净响应内容
        assistantMessage = ChatMessage.ofAssistant(assistantMessage.getContent());

        // 4. 状态回填：将输出结果自动映射到 FlowContext
        if (Assert.isNotEmpty(config.getOutputKey())) {
            context.put(config.getOutputKey(), assistantMessage.getContent());
        }

        // 5. 更新会话状态与快照
        session.addHistoryMessage(config.getName(), assistantMessage);
        session.updateSnapshot(context);

        return assistantMessage;
    }

    /**
     * 组装完整的 Prompt 消息列表（含 SystemPrompt、OutputSchema 及历史窗口）
     */
    private List<ChatMessage> buildMessages(AgentSession session, Prompt prompt){
        String spText = "";

        // 注入基础系统指令
        if (config.getSystemPrompt() != null) {
            spText = config.getSystemPrompt().getSystemPromptFor(session.getSnapshot());
        }

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

        messages.addAll(prompt.getMessages());
        return messages;
    }

    /**
     * 实现带指数延迟的自动重试调用
     */
    private AssistantMessage callWithRetry(AgentSession session, List<ChatMessage> messages) throws Throwable {
        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return doCall(session, messages);
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
    private AssistantMessage doCall(AgentSession session, List<ChatMessage> messages) throws Throwable {
        Prompt finalPrompt = Prompt.of(messages);

        if (config.getChatModel() != null) {
            return config.getChatModel().prompt(finalPrompt)
                    .options(o -> {
                        // 注入 Tools 与上下文
                        config.getTools().forEach(o::toolsAdd);
                        if (Assert.isNotEmpty(config.getToolsContext())) {
                            o.toolsContext(config.getToolsContext());
                        }

                        // 注入拦截器
                        config.getInterceptors().forEach(item -> o.interceptorAdd(item.index, item.target));

                        // 启用 JSON Object 响应格式
                        if (Assert.isNotEmpty(config.getOutputSchema())) {
                            o.optionPut("response_format", Utils.asMap("type", "json_object"));
                        }

                        if (config.getChatOptions() != null) {
                            config.getChatOptions().accept(o);
                        }
                    })
                    .call().getMessage();
        } else {
            // fallback 到自定义处理器
            return config.getHandler().call(finalPrompt, session);
        }
    }

    // Builder 静态方法与内部类保持不变...
    public static Builder of() { return new Builder(); }
    public static Builder of(ChatModel chatModel) { return new Builder().chatModel(chatModel); }

    public static class Builder {
        private SimpleAgentConfig config = new SimpleAgentConfig();

        public Builder then(Consumer<Builder> consumer) { consumer.accept(this); return this; }
        public Builder name(String name) { config.setName(name); return this; }
        public Builder title(String title) { config.setTitle(title); return this; }
        public Builder description(String description) { config.setDescription(description); return this; }
        public Builder profile(AgentProfile profile) { config.setProfile(profile); return this; }
        public Builder chatModel(ChatModel chatModel) { config.setChatModel(chatModel); return this; }
        public Builder systemPrompt(SimpleSystemPrompt systemPrompt) { config.setSystemPrompt(systemPrompt); return this; }
        public Builder handler(AgentHandler handler) { config.setHandler(handler); return this; }
        public Builder chatOptions(Consumer<ChatOptions> chatOptions) { config.setChatOptions(chatOptions); return this; }
        public Builder retryConfig(int maxRetries, long retryDelayMs) { config.setRetryConfig(maxRetries, retryDelayMs); return this; }
        public Builder historyWindowSize(int historyWindowSize){
            config.setHistoryWindowSize(historyWindowSize);
            return this;
        }

        public Builder outputKey(String val) { config.setOutputKey(val); return this; }
        public Builder outputSchema(String val) { config.setOutputSchema(val); return this; }
        public Builder outputSchema(Type type) { config.setOutputSchema(ToolSchemaUtil.buildOutputSchema(type)); return this; }
        public Builder toolAdd(FunctionTool... tools) { config.addTool(tools); return this; }
        public Builder toolAdd(Collection<FunctionTool> tools) { config.addTool(tools); return this; }
        public Builder toolAdd(ToolProvider toolProvider) { config.addTool(toolProvider); return this; }
        public Builder defaultToolsContextPut(String key, Object value) { config.getToolsContext().put(key, value); return this; }
        public Builder defaultInterceptorAdd(ChatInterceptor... vals) {
            for (ChatInterceptor val : vals) config.addInterceptor(val, 0);
            return this;
        }
        public Builder defaultInterceptorAdd(ChatInterceptor val, int index) { config.addInterceptor(val, index); return this; }

        public SimpleAgent build() {
            if (config.getHandler() == null && config.getChatModel() == null)
                throw new IllegalStateException("Handler or ChatModel must be provided");
            if (config.getName() == null) config.setName("simple_agent");
            if (config.getDescription() == null) {
                config.setDescription(config.getTitle() != null ? config.getTitle() : config.getName());
            }
            return new SimpleAgent(config);
        }
    }
}