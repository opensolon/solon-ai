package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

/**
 * 智能体会话接口
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface AgentSession extends NonSerializable {
    /**
     * 获取会话id
     */
    String getSessionId();

    /**
     * 添加历史消息
     */
    void addHistoryMessage(String agentName, ChatMessage message);

    /**
     * 更新快照
     */
    void updateSnapshot(FlowContext snapshot);

    /**
     * 获取快照
     */
    FlowContext getSnapshot();
}