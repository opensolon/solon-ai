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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
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

    @Override
    public String name() { return config.getName(); }

    @Override
    public String title() { return config.getTitle(); }

    @Override
    public String description() { return config.getDescription(); }

    @Override
    public AgentProfile profile() { return config.getProfile(); }


    public SimpleRequest prompt(Prompt prompt) { return new SimpleRequest(this, prompt); }

    public SimpleRequest prompt(String prompt) { return new SimpleRequest(this, Prompt.of(prompt)); }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        return call(prompt, session, null);
    }

    protected AssistantMessage call(Prompt prompt, AgentSession session, Consumer<ChatOptions> chatOptionsAdjustor) throws Throwable {
        if(Prompt.isEmpty(prompt)){
            LOG.warn("Prompt is empty!");
            return ChatMessage.ofAssistant("");
        }


        FlowContext context = session.getSnapshot();

        // 1. 构建请求消息
        List<ChatMessage> messages = buildMessages(session, prompt);

        // 2. 物理调用：执行带重试机制的 LLM 请求
        AssistantMessage result = callWithRetry(session, messages, chatOptionsAdjustor);

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
    private List<ChatMessage> buildMessages(AgentSession session, Prompt prompt) {
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


        // 消息归档：同步当前用户请求到 Session 历史
        if (!Prompt.isEmpty(prompt)) {
            for (ChatMessage message : prompt.getMessages()) {
                session.addHistoryMessage(config.getName(), message);
            }
        }

        return messages;
    }

    /**
     * 实现带指数延迟的自动重试调用
     */
    private AssistantMessage callWithRetry(AgentSession session, List<ChatMessage> messages, Consumer<ChatOptions> chatOptionsAdjustor) throws Throwable {
        if(LOG.isTraceEnabled()){
            LOG.trace("SimpleAgent [{}] calling model... messages: {}",
                    config.getName(),
                    ONode.serialize(messages, Feature.Write_PrettyFormat, Feature.Write_EnumUsingName));
        }

        Prompt finalPrompt = Prompt.of(messages);
        ChatRequestDesc chatReq   = null;

        if (config.getChatModel() != null) {
            //构建 chatModel 请求
            final TeamProtocol protocol = session.getSnapshot().getAs(Agent.KEY_PROTOCOL);
            chatReq = config.getChatModel().prompt(messages)
                    .options(o -> {
                        //配置工具
                        o.toolsAdd(config.getTools());

                        //协议工具
                        if(protocol != null){
                            protocol.injectAgentTools(session.getSnapshot(),this, o::toolsAdd);
                        }

                        o.toolsContextPut(config.getToolsContext());
                        config.getInterceptors().forEach(item -> o.interceptorAdd(item.index, item.target));

                        if (Assert.isNotEmpty(config.getOutputSchema())) {
                            o.optionPut("response_format", Utils.asMap("type", "json_object"));
                        }

                        if (config.getChatOptions() != null) {
                            config.getChatOptions().accept(o);
                        }

                        if (chatOptionsAdjustor != null) {
                            chatOptionsAdjustor.accept(o);
                        }
                    });
        }


        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return doCall(session, finalPrompt, chatReq);
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
    private AssistantMessage doCall(AgentSession session, Prompt finalPrompt, ChatRequestDesc chatReq) throws Throwable {
        if (chatReq != null) {
            // chatModel 处理
            ChatResponse resp = chatReq.call();
            String clearContent = resp.hasContent() ? resp.getResultContent() : "";
            return ChatMessage.ofAssistant(clearContent);
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
        public Builder defaultToolsContextPut(Map<String,Object> objectMap) { config.getToolsContext().putAll(objectMap); return this; }
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