package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;

import java.util.Collection;

/**
 * 智能体会话接口
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface AgentSession extends ChatSession {
    /**
     * 替换所有消息（一般用于压缩）
     */
    void replaceMessages(Collection<ChatMessage> messages);
}