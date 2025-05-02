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

import org.noear.solon.Solon;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.core.BeanWrap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 方法构建的资源提供者
 *
 * @author noear
 * @since 3.2
 */
public class MethodResourceProvider implements ResourceProvider {
    private final List<FunctionResource> resources = new ArrayList<>();

    public MethodResourceProvider(Object toolObj) {
        this(toolObj.getClass(), toolObj);
    }

    public MethodResourceProvider(Class<?> toolClz, Object toolObj) {
        this(new BeanWrap(Solon.context(), toolClz, toolObj));
    }

    public MethodResourceProvider(BeanWrap beanWrap) {
        //添加带注释的工具
        for (Method method : beanWrap.rawClz().getMethods()) {
            if (method.isAnnotationPresent(ResourceMapping.class)) {
                MethodFunctionResource resc = new MethodFunctionResource(beanWrap, method);
                resources.add(resc);
            }
        }

        //如果自己就是工具集，再添加
        if (beanWrap.raw() instanceof ResourceProvider) {
            for (FunctionResource t1 : ((ResourceProvider) beanWrap.raw()).getResources()) {
                resources.add(t1);
            }
        }
    }

    @Override
    public Collection<FunctionResource> getResources() {
        return resources;
    }
}