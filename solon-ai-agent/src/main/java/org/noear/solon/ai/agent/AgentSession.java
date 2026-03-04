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

import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

/**
 * 智能体会话接口
 * <p>负责维护智能体的运行状态、上下文记忆以及执行流快照。
 * 继承自 {@link ChatSession}，扩展了对工作流（Flow）状态的支持。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface AgentSession extends ChatSession, NonSerializable {
    /**
     * 同步/更新执行快照
     */
    void updateSnapshot();

    /**
     * 获取当前状态快照（用于状态回溯或持久化导出）
     */
    FlowContext getSnapshot();
}