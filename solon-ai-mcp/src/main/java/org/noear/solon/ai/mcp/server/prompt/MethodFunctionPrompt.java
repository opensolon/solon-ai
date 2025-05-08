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

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.MethodExecuteHandler;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.wrap.MethodWrap;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 方法构建的函数提示语
 *
 * @author noear
 * @since 3.2
 */
public class MethodFunctionPrompt implements FunctionPrompt {
    private final BeanWrap beanWrap;
    private final MethodWrap methodWrap;

    private final String name;
    private final PromptMapping mapping;
    private final List<ParamDesc> params;

    public MethodFunctionPrompt(BeanWrap beanWrap, Method method) {
        this.beanWrap = beanWrap;
        this.methodWrap = new MethodWrap(beanWrap.context(), method.getDeclaringClass(), method);
        this.mapping = method.getAnnotation(PromptMapping.class);
        this.name = Utils.annoAlias(mapping.name(), method.getName());

        //断言
        Assert.notNull(mapping, "@PromptMapping annotation is missing");

        //断言
        Assert.notEmpty(mapping.description(), "PromptMapping description cannot be empty");

        //检查返回类型
        if (Collection.class.isAssignableFrom(method.getReturnType()) == false) {
            throw new IllegalArgumentException("@PromptMapping return type is not Collection");
        }

        this.params = new ArrayList<>();

        for (Parameter p1 : method.getParameters()) {
            ParamDesc toolParam = ToolSchemaUtil.paramOf(p1);
            if(toolParam != null) {
                this.params.add(toolParam);
            }
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return mapping.description();
    }

    @Override
    public Collection<ParamDesc> params() {
        return params;
    }

    @Override
    public Collection<ChatMessage> handle(Map<String, Object> args) throws Throwable {
        Context ctx = Context.current();
        if (ctx == null) {
            ctx = new ContextEmpty();
        }

        ctx.attrSet(MethodExecuteHandler.MCP_BODY_ATTR, args);

        ctx.result = MethodExecuteHandler.getInstance()
                .executeHandle(ctx, beanWrap.get(), methodWrap);

        return (Collection<ChatMessage>) ctx.result;
    }
}
