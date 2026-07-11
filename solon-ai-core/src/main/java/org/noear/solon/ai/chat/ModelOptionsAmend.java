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

import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 模型选项基类
 *
 * @author noear
 * @since 3.8.4
 */
public class ModelOptionsAmend<T extends ModelOptionsAmend, X> {
    static final String MAX_TOKENS = "max_tokens";
    static final String MAX_COMPLETION_TOKENS = "max_completion_tokens";
    static final String TEMPERATURE = "temperature";
    static final String TOP_P = "top_p";
    static final String TOP_K = "top_k";
    static final String FREQUENCY_PENALTY = "frequency_penalty";
    static final String PRESENCE_PENALTY = "presence_penalty";
    static final String TOOL_CHOICE = "tool_choice";
    static final String RESPONSE_FORMAT = "response_format";
    static final String REASONING_EFFORT = "reasoning_effort";

    protected final AtomicBoolean autoToolCall;

    protected final Map<String, Object> toolContext;
    protected final Map<String, Object> options;

    protected final Map<String, FunctionTool> tools;
    protected final Map<String, RankEntity<Talent>> talents;
    protected final Map<Class<?>, RankEntity<X>> interceptors;


    protected CacheControl cacheControl;

    public ModelOptionsAmend() {
        this.autoToolCall = new AtomicBoolean(true);

        this.toolContext = new LinkedHashMap<>();
        this.options = new LinkedHashMap<>();

        this.tools = new LinkedHashMap<>();
        this.talents = new LinkedHashMap<>();
        this.interceptors = new LinkedHashMap<>();
    }

    public ModelOptionsAmend(ModelOptionsAmend<?, X> real) {
        this.autoToolCall = real.autoToolCall;
        this.toolContext = real.toolContext;
        this.options = real.options;

        this.tools = real.tools;
        this.talents = real.talents;

        this.interceptors = real.interceptors;
    }

    public void putAll(ModelOptionsAmend<?, X> from) {
        if (from != null) {
            autoToolCall.set(from.autoToolCall.get());

            toolContext.putAll(from.toolContext);

            if(Assert.isNotEmpty(from.options)) {
                //支持配置形态，转为旨类型（llm 需要强类型）
                for (Map.Entry<String, Object> entry : from.options.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        String val = (String) entry.getValue();

                        if (Assert.isBoolean(val)) {
                            options.put(entry.getKey(), Boolean.parseBoolean(val));
                        } else if (Assert.isNumber(val)) {
                            if (val.indexOf('.') < 0) {
                                options.put(entry.getKey(), Integer.parseInt(val));
                            } else {
                                options.put(entry.getKey(), Double.parseDouble(val));
                            }
                        } else {
                            options.put(entry.getKey(), val);
                        }
                    } else {
                        options.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            tools.putAll(from.tools);
            talents.putAll(from.talents);
            interceptors.putAll(from.interceptors);

            if(from.cacheControl != null) {
                this.cacheControl = from.cacheControl;
            }
        }
    }

    //===================
    public T autoToolCall(boolean autoToolCall) {
        this.autoToolCall.set(autoToolCall);
        return (T) this;
    }

    /**
     * 是否自动执行工具调用
     *
     */
    public boolean isAutoToolCall() {
        return autoToolCall.get();
    }

    //===================

    /**
     * 获取所有函数工具
     */
    public Collection<FunctionTool> tools() {
        return tools.values();
    }

    /**
     * 添加函数工具
     *
     * @param name 名字
     */
    public FunctionTool tool(String name) {
        return tools.get(name);
    }

    /**
     * 添加函数工具
     */
    public T toolAdd(FunctionTool... tools) {
        for (FunctionTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }

        return (T) this;
    }

    /**
     * 添加函数工具
     */
    public T toolAdd(Collection<FunctionTool> items) {
        if (Assert.isNotEmpty(items)) {
            for (FunctionTool item : items) {
                tools.put(item.name(), item);
            }
        }

        return (T) this;
    }

    /**
     * 添加函数工具
     */
    public T toolAdd(ToolProvider toolProvider) {
        return toolAdd(toolProvider.getTools());
    }

    /**
     * 添加函数工具（构建形式）
     *
     * @param name        名字
     * @param toolBuilder 工具构建器
     */
    public T toolAdd(String name, Consumer<FunctionToolDesc> toolBuilder) {
        FunctionToolDesc decl = new FunctionToolDesc(name);
        toolBuilder.accept(decl);
        tools.put(decl.name(), decl);
        return (T) this;
    }

    //===================

    /**
     * 添加才能
     *
     * @since 3.8.4
     */
    public T talentAdd(Collection<RankEntity<Talent>> items) {
        if (Assert.isNotEmpty(items)) {
            for (RankEntity<Talent> item : items) {
                talents.put(item.target.name(), item);
            }
        }

        return (T) this;
    }

    /**
     * 添加才能
     *
     * @since 3.8.4
     */
    public T talentAdd(Talent... talents) {
        for (Talent t : talents) {
            this.talents.put(t.name(), new RankEntity<>(t, 0));
        }

        return (T) this;
    }

    /**
     * 添加才能
     *
     * @since 3.8.4
     */
    public T talentAdd(int index, Talent talent) {
        talents.put(talent.name(), new RankEntity<>(talent, index));

        return (T) this;
    }

    /**
     * 获取所有才能
     */
    public Collection<RankEntity<Talent>> talents() {
        return talents.values();
    }


    //===================

    /**
     * 工具上下文（附加参数）
     */
    public Map<String, Object> toolContext() {
        return toolContext;
    }

    /**
     * @since 3.8.4
     */
    public T toolContextPut(Map<String, Object> toolsContext) {
        if (Assert.isNotEmpty(toolsContext)) {
            this.toolContext.putAll(toolsContext);
        }
        return (T) this;
    }

    /**
     * @since 3.8.4
     */
    public T toolContextPut(String key, Object val) {
        if (val != null) {
            this.toolContext.put(key, val);
        }
        return (T) this;
    }


    //===================

    /**
     * 添加拦截器
     *
     * @param interceptor 拦截器
     */
    public T interceptorAdd(X interceptor) {
        return interceptorAdd(0, interceptor);
    }

    /**
     * 添加拦截器
     *
     * @param index       顺序位
     * @param interceptor 拦截器
     */
    public T interceptorAdd(int index, X interceptor) {
        interceptors.put(interceptor.getClass(), new RankEntity<>(interceptor, index));

        return (T) this;
    }

    public T interceptorAdd(Collection<RankEntity<X>> items) {
        if (Assert.isNotEmpty(items)) {
            for (RankEntity<X> item : items) {
                interceptors.put(item.target.getClass(), item);
            }
        }

        return (T) this;
    }

    /**
     * 获取所有拦截器
     */
    public Collection<RankEntity<X>> interceptors() {
        return interceptors.values();
    }


    //===================

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
     * 移除选项
     */
    public T optionRemove(String key) {
        options.remove(key);
        return (T) this;
    }

    /**
     * 选项添加
     *
     * @since 3.8.4
     */
    public T optionSet(String key, Object val) {
        options.put(key, val);
        return (T) this;
    }

    public T optionSet(Map<String, Object> map) {
        if (Assert.isNotEmpty(map)) {
            options.putAll(map);
        }

        return (T) this;
    }

    //===================

    /**
     * 函数选择
     *
     * @param choiceOrName 选项或特定函数名
     */
    public T tool_choice(String choiceOrName) {
        if (choiceOrName == null) {
            optionSet(TOOL_CHOICE, "none");
        } else {
            if ("none".equals(choiceOrName)) {
                optionSet(TOOL_CHOICE, "none");
            } else if ("auto".equals(choiceOrName)) {
                optionSet(TOOL_CHOICE, "auto");
            } else if ("required".equals(choiceOrName)) {
                optionSet(TOOL_CHOICE, "required");
            } else {
                Map<String, Object> choiceMap = new HashMap<>();
                choiceMap.put("type", "function");
                choiceMap.put("function", Collections.singletonMap("name", choiceOrName));

                optionSet(TOOL_CHOICE, choiceMap);
            }
        }

        return (T) this;
    }

    /**
     * 常用选项：生成的最大token数
     */
    public T max_tokens(long max_tokens) {
        return optionSet(MAX_TOKENS, max_tokens);
    }

    /**
     * 常用选项：最大完成令牌数限制
     */
    public T max_completion_tokens(long max_completion_tokens) {
        return optionSet(MAX_COMPLETION_TOKENS, max_completion_tokens);
    }

    /**
     * 常用选项：温度（控制输出的随机性，值越高越有创意）
     */
    public T temperature(double temperature) {
        return optionSet(TEMPERATURE, temperature);
    }

    /**
     * 常用选项：top_p 采样（核采样，从累计概率达p的最小词集中选择。替代top_k，更智能的多样性控制）
     */
    public T top_p(double top_p) {
        return optionSet(TOP_P, top_p);
    }

    /**
     * 常用选项：top_k 采样（仅从概率最高的k个词中采样。需要严格限制候选词时）
     */
    public T top_k(double top_k) {
        return optionSet(TOP_K, top_k);
    }

    /**
     * 常用选项：惩罚频繁出现的词
     */
    public T frequency_penalty(double frequency_penalty) {
        return optionSet(FREQUENCY_PENALTY, frequency_penalty);
    }

    /**
     * 常用选项：惩罚已出现过的词
     */
    public T presence_penalty(double frequency_penalty) {
        return optionSet(PRESENCE_PENALTY, frequency_penalty);
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
    public T response_format(Map map) {
        return optionSet(RESPONSE_FORMAT, map);
    }

    /**
     * 常用选项：推理水平（统一语义，由各方言映射到供应商字段）
     * <p>取值：{@code low} / {@code medium} / {@code high} / {@code max}；
     * 传空串、{@code auto} 或 {@code null} 时移除该选项。非法值忽略。</p>
     * <p>方言映射（首批）：anthropic → thinking.budget_tokens；
     * openai Responses → reasoning.effort；openai Chat Completions → reasoning_effort；
     * gemini models → generationConfig.thinkingConfig；gemini interactions → thinking_level。</p>
     *
     * @since 4.0.4
     */
    public T reasoning_effort(String effort) {
        if (effort == null) {
            optionRemove(REASONING_EFFORT);
            return (T) this;
        }
        String normalized = effort.trim().toLowerCase();
        if (normalized.isEmpty() || "auto".equals(normalized)) {
            optionRemove(REASONING_EFFORT);
            return (T) this;
        }
                
        if (!"low".equals(normalized)
                && !"medium".equals(normalized)
                && !"high".equals(normalized)
                && !"max".equals(normalized)) {
            // 非法值忽略，避免污染请求
            return (T) this;
        }
    
        return optionSet(REASONING_EFFORT, normalized);
    }

    /**
     * 用户
     */
    public T user(String user) {
        return optionSet("user", user);
    }

    /**
     * 获取用户
     */
    @Nullable
    public String user() {
        return (String) option("user");
    }

    /// ///////////////////////////////////

    /**
     * 获取缓存控制
     */
    public CacheControl cacheControl() {
        return cacheControl;
    }

    /**
     * 设置缓存控制
     * <p>Anthropic 风格：在系统提示词/工具定义后设置缓存断点，让 LLM 提供商缓存此前缀上下文</p>
     * <p>DeepSeek 风格：通过 prompt_cache_key 指定缓存键，复用相同键的前缀上下文</p>
     */
    public T cacheControl(CacheControl cacheControl) {
        this.cacheControl = cacheControl;
        return (T) this;
    }
}