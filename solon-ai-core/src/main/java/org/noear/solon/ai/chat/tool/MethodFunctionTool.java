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
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.wrap.MethodWrap;

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
    private final BeanWrap beanWrap;
    private final MethodWrap methodWrap;

    private final String name;
    private final String description;
    private final boolean returnDirect;
    private final List<ParamDesc> params = new ArrayList<>();
    private final ToolCallResultConverter resultConverter;
    private final String inputSchema;
    private final String mimeType;

    public MethodFunctionTool(BeanWrap beanWrap, Method method) {
        this.beanWrap = beanWrap;
        this.methodWrap = new MethodWrap(beanWrap.context(), method.getDeclaringClass(), method);

        ToolMapping mapping = method.getAnnotation(ToolMapping.class);

        //断言
        Assert.notNull(mapping, "@ToolMapping annotation is missing");
        //断言
        Assert.notEmpty(mapping.description(), "ToolMapping description cannot be empty");

        this.name = Utils.annoAlias(mapping.name(), method.getName());
        this.description = mapping.description();
        this.returnDirect = mapping.returnDirect();

        Produces producesAnno = method.getAnnotation(Produces.class);
        if (producesAnno != null) {
            this.mimeType = producesAnno.value();
        } else {
            this.mimeType = "";
        }

        if (mapping.resultConverter() == ToolCallResultConverter.class) {
            if (ToolCallResultJsonConverter.getInstance().matched(mimeType)) {
                resultConverter = ToolCallResultJsonConverter.getInstance();
            } else {
                resultConverter = null;
            }
        } else {
            if (Solon.context() != null) {
                resultConverter = Solon.context().getBeanOrNew(mapping.resultConverter());
            } else {
                resultConverter = null;
            }
        }

        for (Parameter p1 : method.getParameters()) {
            ParamDesc toolParam = ToolSchemaUtil.paramOf(p1);
            if (toolParam != null) {
                params.add(toolParam);
            }
        }

        inputSchema = ToolSchemaUtil.buildToolParametersNode(params, new ONode())
                .toJson();
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

    @Override
    public boolean returnDirect() {
        return returnDirect;
    }

    /**
     * 输入架构
     */
    @Override
    public String inputSchema() {
        return inputSchema;
    }

    /**
     * 执行处理
     */
    @Override
    public String handle(Map<String, Object> args) throws Throwable {
        Context ctx = Context.current();
        if (ctx == null) {
            ctx = new ContextEmpty();
        }

        ctx.attrSet(MethodExecuteHandler.MCP_BODY_ATTR, args);

        ctx.result = MethodExecuteHandler.getInstance()
                .executeHandle(ctx, beanWrap.get(), methodWrap);

        if (resultConverter == null) {
            return String.valueOf(ctx.result);
        } else {
            return resultConverter.convert(ctx.result);
        }
    }

    @Override
    public String toString() {
        return "MethodFunctionTool{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", returnDirect=" + returnDirect +
                ", inputSchema=" + inputSchema() +
                '}';
    }
}