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
     * 获取历史消息
     */
    Collection<ChatMessage> getHistoryMessages(String agentName, int last);

    /**
     * 更新快照
     */
    void updateSnapshot(FlowContext snapshot);

    /**
     * 获取快照
     */
    FlowContext getSnapshot();
}