package org.noear.solon.ai.mcp.server.prompt;

import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

/**
 *
 * @author noear 2026/2/10 created
 *
 */
public class PromptResult {
    private final List<ChatMessage> messages = new ArrayList<>();

    public PromptResult(Collection<ChatMessage> messages) {
        this.messages.addAll(messages);
    }

    public int size(){
        return messages.size();
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    @Override
    public String toString() {
        return messages.toString();
    }
}