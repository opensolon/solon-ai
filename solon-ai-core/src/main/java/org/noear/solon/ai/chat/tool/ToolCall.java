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

import org.noear.solon.Utils;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 聊天函数调用
 *
 * @author noear
 * @author xujiaze
 * @since 3.1
 */
public class ToolCall implements Serializable {
    private String uuid;
    private String index;
    private String id;
    private String name;
    private String argumentsStr;
    private Map<String, Object> arguments;
    /**
     * 思考签名（Gemini thinking signature，用于旧消息反序列化与兼容回放）。
     *
     * @since Google Gemini 3 models
     * @deprecated 4.1 新消息使用 AssistantMessage.protocolStates 中的 Gemini 协议状态
     */
    @Deprecated
    private String thoughtSignature;

    public ToolCall() {
        //用于序列化
    }

    public ToolCall(String index, String id, String name, String argumentsStr, Map<String, Object> arguments) {
        //允许拦截器修改参数集合（不要只读）
        this.uuid = Utils.uuid();
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

    public String getUuid() {
        return uuid;
    }

    /**
     * 索引位（流式调用时）
     */
    public String getIndex() {
        return index;
    }

    /**
     * 调用id（用于回传）
     */
    public String getId() {
        return id;
    }

    /**
     * 函数名字
     */
    public String getName() {
        return name;
    }

    /**
     * 调用参数（字符串型式）
     */
    public String getArgumentsStr() {
        return argumentsStr;
    }

    /**
     * 调用参数（字典型式）
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * @deprecated 4.1 仅用于旧 Gemini 消息兼容
     */
    @Deprecated
    public String getThoughtSignature() {
        return thoughtSignature;
    }

    /**
     * @deprecated 4.1 仅为旧 Gemini 消息 JSON Bean 反序列化和源码兼容保留；新解析器不得写入
     */
    @Deprecated
    public void setThoughtSignature(String thoughtSignature) {
        this.thoughtSignature = thoughtSignature;
    }

    @Override
    public String toString() {
        return "ToolCall{" +
                "index='" + index + '\'' +
                ", id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", arguments='" + argumentsStr + '\'' +
                '}';
    }
}