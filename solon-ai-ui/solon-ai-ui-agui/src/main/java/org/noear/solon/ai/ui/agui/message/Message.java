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

import java.util.UUID;

/**
 * AG-UI 协议消息抽象基类，定义所有消息类型共享的基础属性
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/messages">AG-UI Messages</a>
 */
public abstract class Message {
    /** 消息唯一标识 */
    private String id;
    /** 消息文本内容 */
    private String content;
    /** 发送者名称（可选） */
    private String name;

    protected Message() {
        this.id = UUID.randomUUID().toString();
    }

    protected Message(String id, String content, String name) {
        this.id = id;
        this.content = content;
        this.name = name;
    }

    public abstract Role getRole();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}