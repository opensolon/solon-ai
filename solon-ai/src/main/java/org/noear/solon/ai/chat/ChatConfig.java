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
package org.noear.solon.ai.chat;

import org.noear.solon.ai.AiConfig;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.annotation.BindProps;

import java.util.*;

/**
 * 聊天模型配置
 *
 * @author noear
 * @since 3.1
 */
@BindProps(prefix = "solon.ai.chat.*")
public class ChatConfig extends AiConfig {
    private final Map<String, FunctionTool> defaultTools = new LinkedHashMap<>();

    /**
     * 设置默认工具（用于属性提示）
     */
    public void setDefaultTools(Map<String, FunctionTool> tools) {
        if (tools != null) {
            defaultTools.putAll(tools);
        }
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     */
    public void addDefaultTools(FunctionTool tool) {
        defaultTools.put(tool.name(), tool);
    }

    /**
     * 添加默认工具（即每次请求都会带上）
     */
    public void addDefaultTools(Collection<FunctionTool> toolColl) {
        for (FunctionTool f : toolColl) {
            defaultTools.put(f.name(), f);
        }
    }

    /**
     * 获取单个默认工具（即每次请求都会带上）
     *
     * @param name 名字
     */
    public FunctionTool getDefaultTool(String name) {
        return defaultTools.get(name);
    }

    /**
     * 获取所有默认工具（即每次请求都会带上）
     */
    public Collection<FunctionTool> getDefaultTools() {
        return defaultTools.values();
    }
}