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

import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.lang.Preview;

/**
 * 智能体请求构建器（方便进一步扩展）
 * <p>提供链式 API 用于配置和发起对智能体的调用请求。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface AgentRequest {
    /**
     * 配置当前请求所属的会话
     *
     * @param session 智能体会话对象
     * @return 当前请求构建器
     */
    AgentRequest session(AgentSession session);

    /**
     * 执行并获取结果
     *
     * @return AI 响应的消息内容
     * @throws Throwable 执行过程中的异常
     */
    AssistantMessage call() throws Throwable;
}