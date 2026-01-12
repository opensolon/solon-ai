package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentHandler;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.chat.ChatModel;

/**
 * @author noear 2026/1/12 created
 */
public class SimpleAgentConfig {
    private String name;
    private String title;
    private String description;
    private AgentProfile profile;
    private SimpleSystemPrompt systemPrompt;
    private ChatModel chatModel;
    private AgentHandler handler;

    // --- Getter Methods (Public) ---

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AgentProfile getProfile() {
        if(profile == null){
            profile = new AgentProfile();
        }

        return profile;
    }

    public SimpleSystemPrompt getSystemPrompt() {
        return systemPrompt;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public AgentHandler getHandler() {
        return handler;
    }


    // --- Setter Methods (Protected) ---

    protected void setName(String name) {
        this.name = name;
    }

    protected void setTitle(String title) {
        this.title = title;
    }

    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setProfile(AgentProfile profile) {
        this.profile = profile;
    }

    protected void setSystemPrompt(SimpleSystemPrompt systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    protected void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    protected void setHandler(AgentHandler handler) {
        this.handler = handler;
    }
}