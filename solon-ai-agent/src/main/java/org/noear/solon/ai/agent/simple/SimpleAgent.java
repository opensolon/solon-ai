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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 简单智能体实现（专注于 直接响应，无 ReAct 冗余）
 *
 * @author noear 2026/1/12 created
 */
public class SimpleAgent implements Agent {
    private final SimpleAgentConfig config;

    private SimpleAgent(SimpleAgentConfig config) {
        Objects.requireNonNull(config, "Missing config!");

        this.config = config;
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

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        String spText = null;
        if (config.getSystemPrompt() != null) {
            FlowContext context = session.getSnapshot();
            spText = config.getSystemPrompt().getSystemPromptFor(context);
        }

        if (spText != null && !spText.isEmpty()) {
            List<ChatMessage> newMessages = new ArrayList<>();
            newMessages.add(ChatMessage.ofSystem(spText));
            newMessages.addAll(prompt.getMessages());
            prompt = Prompt.of(newMessages);
        }

        if (config.getChatModel() != null) {
            return config.getChatModel().prompt(prompt).call().getMessage();
        } else {
            return config.getHandler().call(prompt, session);
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
        private SimpleAgentConfig config = new SimpleAgentConfig();

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

        public Builder handler(AgentHandler handler) {
            config.setHandler(handler);
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
            if (config.getHandler() == null && config.getChatModel() == null) {
                throw new IllegalStateException("Handler or ChatModel must be provided for SimpleAgent");
            }

            if (config.getName() == null) {
                config.setName("simple_agent");
            }

            if (config.getDescription() == null) {
                if (config.getTitle() != null) {
                    config.setDescription(config.getTitle());
                } else {
                    config.setDescription(config.getName());
                }
            }

            return new SimpleAgent(config);
        }
    }
}