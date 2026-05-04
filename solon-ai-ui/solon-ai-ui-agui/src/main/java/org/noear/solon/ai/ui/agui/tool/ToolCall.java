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
package org.noear.solon.ai.ui.agui.tool;

/**
 * AG-UI 协议工具调用引用，包含调用标识、类型和具体的函数调用信息
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/messages#assistant-message">AG-UI ToolCall</a>
 */
public class ToolCall {
    /** 工具调用唯一标识 */
    private final String id;
    /** 工具类型（通常为 "function"） */
    private final String type;
    /** 函数调用详情 */
    private final FunctionCall function;

    public ToolCall(String id, String type, FunctionCall function) {
        this.id = id;
        this.type = type;
        this.function = function;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public FunctionCall getFunction() {
        return function;
    }
}