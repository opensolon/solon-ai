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

import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.flow.FlowContext;

/**
 * 智能体响应
 *
 * @author noear
 * @since 3.8.4
 */
public interface AgentResponse {
    /**
     * 获取会话
     */
    AgentSession getSession();

    /**
     * 获取上下文
     * */
    FlowContext getContext();

    /**
     * 获取执行指标（如 Token 消耗、耗时等）
     */
    Metrics getMetrics();

    /**
     * 获取消息
     */
    AssistantMessage getMessage();

    /**
     * 获取消息内容
     */
    String getContent();

    /**
     * 将消息内容转换为结构化对象（通常用于解析 JSON 格式的 Final Answer）
     *
     * @param type 目标类型
     * @param <T>  类型泛型
     */
    <T> T toBean(Class<T> type);
}
