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
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.util.Collection;

/**
 * 智能体会话（运行状态与记忆中心）
 *
 * <p>核心职责：管理智能体的长短期记忆（History）与业务执行快照（Snapshot）。</p>
 * <ul>
 * <li><b>多轮对话：</b>按智能体维度隔离并持久化对话足迹。</li>
 * <li><b>状态机平衡：</b>通过 Snapshot 机制同步 Flow 工作流上下文。</li>
 * </ul>
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
     * 追加历史消息（存入短期记忆）
     *
     * @param agentName 智能体标识
     * @param message   消息内容
     */
    void addHistoryMessage(String agentName, ChatMessage message);

    /**
     * 提取最近历史消息（记忆加载）
     *
     * @param agentName 智能体标识
     * @param last      提取最近的消息条数
     * @return 历史消息列表
     */
    Collection<ChatMessage> getHistoryMessages(String agentName, int last);

    /**
     * 同步/更新执行快照
     *
     * @param snapshot 包含最新业务数据的上下文
     */
    void updateSnapshot(FlowContext snapshot);

    /**
     * 获取当前状态快照（用于状态回溯或持久化导出）
     */
    FlowContext getSnapshot();
}