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

import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;

/**
 * 智能体处理器（执行逻辑单元）
 *
 * <p>核心职责：定义智能体如何处理输入请求并生成响应。它是 Agent 行为的具体实现载体。</p>
 * <ul>
 * <li><b>独立性：</b>可以是一个简单的模型调用，也可以是一段复杂的业务代码或工作流。</li>
 * <li><b>状态关联：</b>通过 {@link AgentSession} 访问历史记忆或更新执行快照。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
@FunctionalInterface
public interface AgentHandler {
    /**
     * 执行处理逻辑
     *
     * @param prompt  当前输入的提示词（包含上下文消息）
     * @param session 当前智能体会话（用于记忆存取）
     * @return 智能体生成的响应消息
     * @throws Throwable 执行过程中的异常处理
     */
    AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable;
}