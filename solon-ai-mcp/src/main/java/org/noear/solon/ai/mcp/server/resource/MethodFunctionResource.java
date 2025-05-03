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
package org.noear.solon.ai.mcp.server.resource;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.chat.tool.MethodExecuteHandler;
import org.noear.solon.ai.media.Text;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.PathMatcher;
import org.noear.solon.core.util.PathUtil;
import org.noear.solon.core.wrap.MethodWrap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
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
    private final MethodWrap methodWrap;

    private final String name;
    private ResourceMapping mapping;

    //path 分析器
    private PathMatcher pathKeysMatcher;//路径分析器
    //path key 列表
    private List<String> pathKeys;

    public MethodFunctionResource(BeanWrap beanWrap, Method method) {
        this.beanWrap = beanWrap;
        this.methodWrap = beanWrap.context().methodGet(method);
        this.mapping = method.getAnnotation(ResourceMapping.class);
        this.name = Utils.annoAlias(mapping.name(), method.getName());

        //断言
        Assert.notNull(mapping, "@ResourceMapping annotation is missing");

        //断言
        Assert.notEmpty(mapping.description(), "ResourceMapping description cannot be empty");

        //检查返回类型
        if (Text.class.isAssignableFrom(method.getReturnType()) == false &&
                String.class.equals(method.getReturnType()) == false &&
                byte[].class.equals(method.getReturnType()) == false) {
            throw new IllegalArgumentException("@ResourceMapping return type is not 'Text' or 'String' or `byte[]`");
        }

        //支持path变量
        if (mapping.uri() != null && mapping.uri().indexOf('{') >= 0) {
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
        return name;
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
    public Text handle(String reqUri) throws Throwable {
        Context ctx = Context.current();
        if (ctx == null) {
            ctx = new ContextEmpty();
        }

        bindPathVarDo(ctx, reqUri);

        ctx.result = MethodExecuteHandler.getInstance()
                .executeHandle(ctx, beanWrap.get(), methodWrap);

        if (ctx.result instanceof Text) {
            return (Text) ctx.result;
        } else if (ctx.result instanceof byte[]) {
            String blob = Base64.getEncoder().encodeToString((byte[]) ctx.result);
            return Text.of(true, blob);
        } else {
            return Text.of(false, String.valueOf(ctx.result));
        }
    }

    private void bindPathVarDo(Context c, String reqUri) throws Throwable {
        if (pathKeysMatcher != null) {
            Matcher pm = pathKeysMatcher.matcher(reqUri);
            if (pm.find()) {
                for (int i = 0, len = pathKeys.size(); i < len; i++) {
                    c.paramMap().add(pathKeys.get(i), pm.group(i + 1));//不采用group name,可解决_的问题
                }
            }
        }
    }
}