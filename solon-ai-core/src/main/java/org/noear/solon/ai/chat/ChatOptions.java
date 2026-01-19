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

import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.Nullable;
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
    public static final String RESPONSE_FORMAT = "response_format";


    public static ChatOptions of() {
        return new ChatOptions();
    }


    private boolean autoToolCall = true;
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    private final List<RankEntity<Skill>> skills = new ArrayList<>();
    private final List<RankEntity<ChatInterceptor>> interceptors = new ArrayList<>();
    private final Map<String, Object> options = new LinkedHashMap<>();

    /// ////////////

    public ChatOptions autoToolCall(boolean autoToolCall) {
        this.autoToolCall = autoToolCall;
        return this;
    }

    /**
     * 是否自动执行工具调用
     * */
    public boolean isAutoToolCall() {
        return autoToolCall;
    }

    /**
     * 工具上下文（附加参数）
     */
    public Map<String, Object> toolsContext() {
        return toolsContext;
    }

    /**
     * @deprecated 3.8.4 {@link #toolsContextPut(Map)}
     */
    @Deprecated
    public ChatOptions toolsContext(Map<String, Object> toolsContext) {
       return toolsContextPut(toolsContext);
    }

    /**
     * @since 3.8.4
     */
    public ChatOptions toolsContextPut(Map<String, Object> toolsContext) {
        if (Assert.isNotEmpty(toolsContext)) {
            this.toolsContext.putAll(toolsContext);
        }
        return this;
    }

    /**
     * @since 3.8.4
     */
    public ChatOptions toolsContextPut(String key, String val) {
        this.toolsContext.put(key, val);
        return this;
    }

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
        if(toolColl != null) {
            for (FunctionTool f : toolColl) {
                tools.put(f.name(), f);
            }
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

    protected ChatOptions skillAdd(Collection<RankEntity<Skill>> skills2) {
        skills.addAll(skills2);

        if(skills.size() > 0){
            Collections.sort(skills);
        }

        return this;
    }

    public ChatOptions skillAdd(Skill skill) {
        return skillAdd(0, skill);
    }

    public ChatOptions skillAdd(int index, Skill skill) {
        skills.add(new RankEntity<>(skill, index));
        return this;
    }

    public List<RankEntity<Skill>> skills() {
        return skills;
    }

    /// ///////////////////////////////////

    /**
     * 添加拦截器
     *
     * @param interceptor 拦截器
     */
    public ChatOptions interceptorAdd(ChatInterceptor interceptor) {
        return interceptorAdd(0, interceptor);
    }

    /**
     * 添加拦截器
     *
     * @param index       顺序位
     * @param interceptor 拦截器
     */
    public ChatOptions interceptorAdd(int index, ChatInterceptor interceptor) {
        interceptors.add(new RankEntity<>(interceptor, index));
        return this;
    }

    /**
     * 获取所有拦截器
     */
    public List<RankEntity<ChatInterceptor>> interceptors() {
        return interceptors;
    }

    /// ///////////////////////////////////


    /**
     * 所有选项
     */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * 移除选项
     */
    public ChatOptions optionRemove(String key) {
        options.remove(key);
        return this;
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
    public ChatOptions optionPut(String key, Object val) {
        options.put(key, val);
        return this;
    }

    /**
     * 选项添加
     * 
     * @deprecated 3.8.1 {@link #optionPut(String, Object)}
     */
    @Deprecated
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
            optionPut(TOOL_CHOICE, "none");
        } else {
            if ("none".equals(choiceOrName)) {
                optionPut(TOOL_CHOICE, "none");
            } else if ("auto".equals(choiceOrName)) {
                optionPut(TOOL_CHOICE, "auto");
            } else if ("required".equals(choiceOrName)) {
                optionPut(TOOL_CHOICE, "required");
            } else {
                Map<String, Object> choiceMap = new HashMap<>();
                choiceMap.put("type", "function");
                choiceMap.put("function", Collections.singletonMap("name", choiceOrName));

                optionPut(TOOL_CHOICE, choiceMap);
            }
        }

        return this;
    }

    /**
     * 常用选项：生成的最大token数
     */
    public ChatOptions max_tokens(long max_tokens) {
        return optionPut(MAX_TOKENS, max_tokens);
    }

    /**
     * 常用选项：最大完成令牌数限制
     */
    public ChatOptions max_completion_tokens(long max_completion_tokens) {
        return optionPut(MAX_COMPLETION_TOKENS, max_completion_tokens);
    }

    /**
     * 常用选项：温度（控制输出的随机性，值越高越有创意）
     */
    public ChatOptions temperature(float temperature) {
        return optionPut(TEMPERATURE, temperature);
    }

    /**
     * 常用选项：top_p 采样（核采样，从累计概率达p的最小词集中选择。替代top_k，更智能的多样性控制）
     */
    public ChatOptions top_p(float top_p) {
        return optionPut(TOP_P, top_p);
    }

    /**
     * 常用选项：top_k 采样（仅从概率最高的k个词中采样。需要严格限制候选词时）
     */
    public ChatOptions top_k(float top_k) {
        return optionPut(TOP_K, top_k);
    }

    /**
     * 常用选项：惩罚频繁出现的词
     */
    public ChatOptions frequency_penalty(float frequency_penalty) {
        return optionPut(FREQUENCY_PENALTY, frequency_penalty);
    }

    /**
     * 常用选项：惩罚已出现过的词
     */
    public ChatOptions presence_penalty(float frequency_penalty) {
        return optionPut(PRESENCE_PENALTY, frequency_penalty);
    }

    /**
     * 常用选项：响应格式
     * <pre>{@code
     * o.response_format(Utils.asMap("type", "json_object"));
     *
     * o.response_format(Utils.asMap("type", "json_schema",
     *                               "json_schema", Utils.asMap("type","object","properties",Utils.asMap()),
     *                               "strict", true));
     * }</pre>
     */
    public ChatOptions response_format(Map map) {
        return optionPut(RESPONSE_FORMAT, map);
    }

    /**
     * 用户
     */
    public ChatOptions user(String user) {
        return optionPut("user", user);
    }

    /**
     * 获取用户
     */
    @Nullable
    public String user() {
        return (String) option("user");
    }
}