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
package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.util.Collection;

/**
 * 智能体会话接口
 *
 * <p>负责维护智能体的运行状态、长短期记忆（对话历史）以及底层工作流的执行快照。
 * 该接口实现通常是非序列化的，但支持通过快照机制进行持久化。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface AgentSession extends NonSerializable {
    /**
     * 获取会话唯一标识符
     */
    String getSessionId();

    /**
     * 向会话中追加一条历史消息（记忆注入）
     *
     * @param agentName 产生消息的智能体名称
     * @param message   对话消息内容
     */
    void addHistoryMessage(String agentName, ChatMessage message);

    /**
     * 获取指定智能体的最近历史消息（记忆提取）
     *
     * @param agentName 智能体名称
     * @param last      提取最近的消息数量
     * @return 历史消息集合
     */
    Collection<ChatMessage> getHistoryMessages(String agentName, int last);

    /**
     * 更新会话执行状态快照
     *
     * @param snapshot 包含最新状态的流上下文
     */
    void updateSnapshot(FlowContext snapshot);

    /**
     * 获取当前会话的执行状态快照
     * <p>常用于将当前会话状态导出、持久化或传递给后续流程节点。</p>
     *
     * @return 底层流上下文对象
     */
    FlowContext getSnapshot();
}