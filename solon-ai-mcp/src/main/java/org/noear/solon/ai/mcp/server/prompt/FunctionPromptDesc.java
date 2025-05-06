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
package org.noear.solon.ai.mcp.server.prompt;

import org.noear.snack.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.util.ParamDesc;

import java.util.*;
import java.util.function.Function;

/**
 * 函数提示语描述
 *
 * @author noear
 * @since 3.2
 */
public class FunctionPromptDesc implements FunctionPrompt {
    private final String name;
    private final List<ParamDesc> params;

    private String description;
    private Function<Map<String, Object>, Collection<ChatMessage>> doHandler;

    public FunctionPromptDesc(String name) {
        this.name = name;
        this.params = new ArrayList<>();
    }

    /**
     * 申明函数描述
     *
     * @param description 参数
     */
    public FunctionPromptDesc description(String description) {
        this.description = description;
        return this;
    }


    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionPromptDesc param(String name,String description) {
        return param(name,  true, description);
    }

    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param required    是否必须
     * @param description 参数描述
     */
    public FunctionPromptDesc param(String name, boolean required, String description) {
        params.add(new ParamDesc(name, String.class, required, description));
        return this;
    }


    /**
     * 申明函数处理
     *
     * @param handler 处理器
     */
    public FunctionPromptDesc doHandle(Function<Map<String, Object>, Collection<ChatMessage>> handler) {
        this.doHandler = handler;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Collection<ParamDesc> params() {
        return params;
    }

    @Override
    public Collection<ChatMessage> handle(Map<String, Object> args) throws Throwable {
        Map<String, Object> argsNew = new HashMap<>();

        ONode argsNode = ONode.load(args);
        for (ParamDesc p1 : this.params) {
            ONode v1 = argsNode.getOrNull(p1.name());
            if (v1 == null) {
                //null
                argsNew.put(p1.name(), null);
            } else {
                //用 ONode 可以自动转换类型
                argsNew.put(p1.name(), v1.toObject(p1.type()));
            }
        }

        return doHandler.apply(argsNew);
    }

    @Override
    public String toString() {
        return "FunctionPromptDesc{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", params=" + params +
                '}';
    }
}