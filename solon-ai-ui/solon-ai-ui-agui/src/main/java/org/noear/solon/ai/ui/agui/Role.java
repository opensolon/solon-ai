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
package org.noear.solon.ai.ui.agui;

/**
 * AG-UI 协议消息角色枚举
 * <p>
 * 定义消息发送者的身份：assistant（助手）、developer（开发者）、system（系统）、tool（工具）、user（用户）。
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events">AG-UI Events</a>
 */
public enum Role {
    ASSISTANT("assistant"),
    DEVELOPER("developer"),
    SYSTEM("system"),
    TOOL("tool"),
    USER("user");

    /** 角色编码 */
    private final String code;

    Role(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
