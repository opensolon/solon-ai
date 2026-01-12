package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentHandler;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * 简单智能体实现（专注于 直接响应，无 ReAct 冗余）
 *
 * @author noear 2026/1/12 created
 */
public class SimpleAgent implements Agent {
    private final String name;
    private final String title;
    private final String description;
    private final AgentProfile profile;
    private final SimpleSystemPrompt systemPrompt;

    private final ChatModel chatModel;
    private final AgentHandler handler;

    private SimpleAgent(String name, String title, String description, AgentProfile profile, SimpleSystemPrompt systemPrompt, ChatModel chatModel, AgentHandler handler) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.profile = profile;
        this.systemPrompt = systemPrompt;

        this.chatModel = chatModel;
        this.handler = handler;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public AgentProfile profile() {
        return profile;
    }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        String spText = null;
        if (systemPrompt != null) {
            FlowContext context = session.getSnapshot();
            spText = systemPrompt.getSystemPromptFor(context);
        }

        if (spText != null && !spText.isEmpty()) {
            List<ChatMessage> newMessages = new ArrayList<>();
            newMessages.add(ChatMessage.ofSystem(spText));
            newMessages.addAll(prompt.getMessages());
            prompt = Prompt.of(newMessages);
        }

        if (chatModel != null) {
            return chatModel.prompt(prompt).call().getMessage();
        } else {
            return handler.call(prompt, session);
        }
    }

    /**
     * 开启构建流程
     */
    public static Builder of() {
        return new Builder();
    }

    /**
     * 快速基于 ChatModel 构建一个 SimpleAgent
     */
    public static Builder of(ChatModel chatModel) {
        return new Builder().chatModel(chatModel);
    }

    /**
     * 智能体构建器
     */
    public static class Builder {
        private String name;
        private String title;
        private String description;
        private AgentProfile profile;
        private SimpleSystemPrompt systemPrompt;
        private ChatModel chatModel;
        private AgentHandler handler;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder profile(AgentProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder systemPrompt(SimpleSystemPrompt systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder handler(AgentHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * 链式调用增强器
         */
        public Builder then(Consumer<Builder> consumer) {
            consumer.accept(this);
            return this;
        }

        public SimpleAgent build() {
            if (handler == null && chatModel == null) {
                throw new IllegalStateException("Handler or ChatModel must be provided for SimpleAgent");
            }

            if (profile == null) {
                profile = new AgentProfile();
            }

            return new SimpleAgent(name, title, description, profile, systemPrompt, chatModel, handler);
        }
    }
}