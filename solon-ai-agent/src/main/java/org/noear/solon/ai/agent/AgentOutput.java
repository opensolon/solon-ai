/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;

/**
 * 智能体输出（用于流式输出的数据包）
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public interface AgentOutput {
    /**
     * 获取当前产生输出的智能体名字
     */
    String getAgentName();

    /**
     * 获取对应的流程节点 ID（用于溯源）
     */
    String getNodeId();

    /**
     * 获取当前输出所属的会话
     */
    AgentSession getSession();

    /**
     * 获取当前流片段的消息内容
     */
    ChatMessage getMessage();
}