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

import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;

/**
 * 聊天选项
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class ChatOptions {
    public static final String MAX_TOKENS = "max_tokens";
    public static final String MAX_COMPLETION_TOKENS = "max_completion_tokens";
    public static final String TEMPERATURE = "temperature";
    public static final String TOP_P = "top_p";
    public static final String TOP_K = "top_k";
    public static final String FREQUENCY_PENALTY = "frequency_penalty";
    public static final String PRESENCE_PENALTY = "presence_penalty";
    public static final String TOOL_CHOICE = "tool_choice";


    public static ChatOptions of() {
        return new ChatOptions();
    }


    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    private Map<String, Object> options = new LinkedHashMap<>();

    /// ////////////

    /**
     * 所有工具
     */
    public Collection<FunctionTool> tools() {
        return tools.values();
    }

    /**
     * 工具获取
     *
     * @param name 名字
     */
    public FunctionTool tool(String name) {
        return tools.get(name);
    }

    /**
     * 工具添加
     */
    public ChatOptions toolsAdd(FunctionTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    /**
     * 工具添加
     */
    public ChatOptions toolsAdd(Iterable<FunctionTool> toolColl) {
        for (FunctionTool f : toolColl) {
            tools.put(f.name(), f);
        }

        return this;
    }

    /**
     * 工具添加
     */
    public ChatOptions toolsAdd(ToolProvider toolProvider) {
        return toolsAdd(toolProvider.getTools());
    }

    /**
     * 工具添加
     *
     * @param toolObj 工具对象
     */
    public ChatOptions toolsAdd(Object toolObj) {
        return toolsAdd(new MethodToolProvider(toolObj));
    }

    /**
     * 工具添加（构建形式）
     *
     * @param name        名字
     * @param toolBuilder 工具构建器
     */
    public ChatOptions toolsAdd(String name, Consumer<FunctionToolDesc> toolBuilder) {
        FunctionToolDesc decl = new FunctionToolDesc(name);
        toolBuilder.accept(decl);
        tools.put(decl.name(), decl);
        return this;
    }

    /// ///////////////////////////////////


    /**
     * 所有选项
     */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * 选项获取
     */
    public Object option(String key) {
        return options.get(key);
    }

    /**
     * 选项添加
     */
    public ChatOptions optionAdd(String key, Object val) {
        options.put(key, val);
        return this;
    }

    /// ///////////////////////////////////

    /**
     * 函数选择
     *
     * @param choiceOrName 选项或特定函数名
     */
    public ChatOptions tool_choice(String choiceOrName) {
        if (choiceOrName == null) {
            optionAdd(TOOL_CHOICE, "none");
        } else {
            if ("none".equals(choiceOrName)) {
                optionAdd(TOOL_CHOICE, "none");
            } else if ("auto".equals(choiceOrName)) {
                optionAdd(TOOL_CHOICE, "auto");
            } else if ("required".equals(choiceOrName)) {
                optionAdd(TOOL_CHOICE, "required");
            } else {
                Map<String, Object> choiceMap = new HashMap<>();
                choiceMap.put("type", "function");
                choiceMap.put("function", Collections.singletonMap("name", choiceOrName));

                optionAdd(TOOL_CHOICE, choiceMap);
            }
        }

        return this;
    }

    /**
     * 常用选项：最大提示语令牌数限制
     */
    public ChatOptions max_tokens(long max_tokens) {
        return optionAdd(MAX_TOKENS, max_tokens);
    }

    /**
     * 常用选项：最大完成令牌数限制
     */
    public ChatOptions max_completion_tokens(long max_completion_tokens) {
        return optionAdd(MAX_COMPLETION_TOKENS, max_completion_tokens);
    }

    /**
     * 常用选项：temperature 采样
     */
    public ChatOptions temperature(float temperature) {
        return optionAdd(TEMPERATURE, temperature);
    }

    /**
     * 常用选项：top_p 采样
     */
    public ChatOptions top_p(float top_p) {
        return optionAdd(TOP_P, top_p);
    }

    /**
     * 常用选项：top_k 采样
     */
    public ChatOptions top_k(float top_k) {
        return optionAdd(TOP_K, top_k);
    }

    /**
     * 常用选项：频率惩罚
     */
    public ChatOptions frequency_penalty(float frequency_penalty) {
        return optionAdd(FREQUENCY_PENALTY, frequency_penalty);
    }

    /**
     * 常用选项：存在惩罚
     */
    public ChatOptions presence_penalty(float frequency_penalty) {
        return optionAdd(PRESENCE_PENALTY, frequency_penalty);
    }

    /**
     * 用户
     */
    public ChatOptions user(String user) {
        return optionAdd("user", user);
    }
}