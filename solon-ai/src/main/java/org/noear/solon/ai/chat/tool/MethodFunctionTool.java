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
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 方法构建的函数工具
 *
 * @author noear
 * @since 3.1
 */
public class MethodFunctionTool implements FunctionTool {
    private final Object target;
    private final Method method;
    private final String description;
    private final String name;
    private final List<FunctionToolParam> params = new ArrayList<>();

    public MethodFunctionTool(Object target, Method method) {
        this.target = target;
        this.method = method;

        ToolMapping m1Anno = method.getAnnotation(ToolMapping.class);
        this.name = Utils.annoAlias(m1Anno.name(), method.getName());
        this.description = m1Anno.description();

        for (Parameter p1 : method.getParameters()) {
            ToolParam p1Anno = p1.getAnnotation(ToolParam.class);

            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            params.add(new FunctionToolParamDesc(name, p1.getType(), p1Anno.required(), p1Anno.description()));
        }
    }

    /**
     * 名字
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 描述
     */
    @Override
    public String description() {
        return description;
    }

    /**
     * 参数
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

        return doHandle(argsNew);
    }

    private String doHandle(Map<String, Object> args) throws Throwable {
        Object[] vals = new Object[params.size()];

        for (int i = 0; i < params.size(); ++i) {
            vals[i] = args.get(params.get(i).name());
        }

        Object rst = method.invoke(target, vals);
        return String.valueOf(rst);
    }

    @Override
    public String toString() {
        return "MethodFunctionTool{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", inputSchema=" + inputSchema() +
                '}';
    }
}