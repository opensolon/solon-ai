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
package org.noear.solon.ai.chat.tool;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 聊天函数调用
 *
 * @author noear
 * @since 3.1
 */
public class ToolCall implements Serializable {
    private String index;
    private String id;
    private String name;
    private String argumentsStr;
    private Map<String, Object> arguments;

    public ToolCall() {
        //用于序列化
    }

    public ToolCall(String index, String id, String name, String argumentsStr, Map<String, Object> arguments) {
        //允许拦截器修改参数集合（不要只读）
        this.index = index;
        this.id = id;
        this.name = name;

        this.argumentsStr = argumentsStr;

        if (arguments == null) {
            this.arguments = new HashMap<>();
        } else {
            this.arguments = arguments;
        }
    }

    /**
     * 索引位（流式调用时）
     */
    public String index() {
        return index;
    }

    /**
     * 调用id（用于回传）
     */
    public String id() {
        return id;
    }

    /**
     * 函数名字
     */
    public String name() {
        return name;
    }

    /**
     * 调用参数（字符串型式）
     */
    public String argumentsStr() {
        return argumentsStr;
    }

    /**
     * 调用参数（字典型式）
     */
    public Map<String, Object> arguments() {
        return arguments;
    }
}