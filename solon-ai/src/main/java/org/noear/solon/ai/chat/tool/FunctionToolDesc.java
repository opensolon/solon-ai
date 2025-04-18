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

import org.noear.snack.ONode;

import java.util.*;
import java.util.function.Function;

/**
 * 函数工具描述（相当于构建器）
 *
 * @author noear
 * @since 3.1
 */
public class FunctionToolDesc implements FunctionTool {
    private final String name;
    private final List<FunctionToolParam> params;
    private String description;
    private Function<Map<String, Object>, String> doHandle;

    /**
     * @param name 函数名字
     */
    public FunctionToolDesc(String name) {
        this.name = name;
        this.params = new ArrayList<>();
    }

    /**
     * 申明函数描述
     *
     * @param description 参数
     */
    public FunctionToolDesc description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param type        参数类型
     * @param required    是否必须
     * @param description 参数描述
     */
    public FunctionToolDesc param(String name, Class<?> type, boolean required, String description) {
        params.add(new FunctionToolParamDesc(name, type, required, description));
        return this;
    }

    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param type        参数类型
     * @param description 参数描述
     */
    public FunctionToolDesc param(String name, Class<?> type, String description) {
        params.add(new FunctionToolParamDesc(name, type, true, description));
        return this;
    }

    /**
     * 申明函数字符串参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc stringParam(String name, String description) {
        return param(name, String.class, description);
    }

    /**
     * 申明函数整型参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc intParam(String name, String description) {
        return param(name, int.class, description);
    }

    /**
     * 申明函数浮点数参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc floatParam(String name, String description) {
        return param(name, float.class, description);
    }

    /**
     * 申明函数布尔参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc boolParam(String name, String description) {
        return param(name, Boolean.class, description);
    }

    /**
     * 申明函数时间参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc dateParam(String name, String description) {
        return param(name, Date.class, description);
    }

    /**
     * 申明函数处理
     *
     * @param handler 处理器
     */
    public FunctionToolDesc doHandle(Function<Map<String, Object>, String> handler) {
        this.doHandle = handler;
        return this;
    }

    /// /////////////////////

    /**
     * 函数名字
     */
    @Override
    public String name() {
        return name;
    }


    /**
     * 函数描述
     */
    @Override
    public String description() {
        return description;
    }


    /**
     * 函数参数
     */
    public List<FunctionToolParam> params() {
        return Collections.unmodifiableList(params);
    }

    /**
     * 输入架构
     */
    @Override
    public ONode inputSchema() {
        return ToolSchemaUtil.buildToolParametersNode(this, params, new ONode());
    }

    /**
     * 执行处理
     */
    @Override
    public String handle(Map<String, Object> args) throws Throwable {
        Map<String, Object> argsNew = new HashMap<>();

        ONode argsNode = ONode.load(args);
        for (FunctionToolParam p1 : this.params()) {
            ONode v1 = argsNode.getOrNull(p1.name());
            if (v1 == null) {
                //null
                argsNew.put(p1.name(), null);
            } else {
                //用 ONode 可以自动转换类型
                argsNew.put(p1.name(), v1.toObject(p1.type()));
            }
        }

        return doHandle.apply(argsNew);
    }
}