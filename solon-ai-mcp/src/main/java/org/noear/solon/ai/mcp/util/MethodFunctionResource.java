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
package org.noear.solon.ai.mcp.util;

import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.MethodHandler;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.PathMatcher;
import org.noear.solon.core.util.PathUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 方法构建的函数资源
 *
 * @author noear
 * @since 3.2
 */
public class MethodFunctionResource implements FunctionResource {
    private BeanWrap beanWrap;
    private Method method;
    private MethodHandler methodHandler;
    private ResourceMapping mapping;

    //path 分析器
    private PathMatcher pathKeysMatcher;//路径分析器
    //path key 列表
    private List<String> pathKeys;

    public MethodFunctionResource(BeanWrap beanWrap, Method method) {
        this.beanWrap = beanWrap;
        this.method = method;
        this.mapping = method.getAnnotation(ResourceMapping.class);

        //断言
        Assert.notNull(mapping, "@ResourceMapping annotation is missing");

        //断言
        Assert.notEmpty(mapping.description(), "ResourceMapping description cannot be empty");

        this.methodHandler = new MethodHandler(beanWrap, method, true);

        //支持path变量
        if (mapping.uri() != null && mapping.uri().contains("{")) {
            pathKeys = new ArrayList<>();
            Matcher pm = PathUtil.pathKeyExpr.matcher(mapping.uri());
            while (pm.find()) {
                pathKeys.add(pm.group(1));
            }

            if (pathKeys.size() > 0) {
                pathKeysMatcher = PathMatcher.get(mapping.uri());
            }
        }
    }

    @Override
    public String uri() {
        return mapping.uri();
    }

    @Override
    public String name() {
        return mapping.name();
    }

    @Override
    public String description() {
        return mapping.description();
    }

    @Override
    public String mimeType() {
        return mapping.mimeType();
    }

    @Override
    public String handle(String reqUri) throws Throwable {
        Context ctx = new ContextEmpty();
        ctx.pathNew(reqUri);

        //获取path var
        bindPathVarDo(ctx);

        methodHandler.handle(ctx);

        return String.valueOf(ctx.result);
    }

    private void bindPathVarDo(Context c) throws Throwable {
        if (pathKeysMatcher != null) {
            Matcher pm = pathKeysMatcher.matcher(c.pathNew());
            if (pm.find()) {
                for (int i = 0, len = pathKeys.size(); i < len; i++) {
                    c.paramMap().add(pathKeys.get(i), pm.group(i + 1));//不采用group name,可解决_的问题
                }
            }
        }
    }
}