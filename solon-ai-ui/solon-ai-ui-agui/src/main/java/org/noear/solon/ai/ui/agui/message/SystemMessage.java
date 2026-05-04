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
package org.noear.solon.ai.ui.agui.message;

import org.noear.solon.ai.ui.agui.Role;

/**
 * AG-UI 系统消息，用于向 Agent 提供系统级指令或约束
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/messages#system-message">AG-UI SystemMessage</a>
 */
public class SystemMessage extends Message {
    public SystemMessage() {
        super();
    }

    public SystemMessage(String id, String content, String name) {
        super(id, content, name);
    }

    @Override
    public Role getRole() {
        return Role.SYSTEM;
    }
}