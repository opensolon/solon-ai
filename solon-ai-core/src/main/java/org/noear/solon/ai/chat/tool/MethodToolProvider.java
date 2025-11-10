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

import org.noear.eggg.ClassEggg;
import org.noear.eggg.MethodEggg;
import org.noear.solon.Solon;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.util.EgggUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 方法构建的工具提供者
 *
 * @author noear
 * @since 3.1
 */
public class MethodToolProvider implements ToolProvider {
    private final List<FunctionTool> tools = new ArrayList<>();

    public MethodToolProvider(Object toolObj) {
        this(toolObj.getClass(), toolObj);
    }

    public MethodToolProvider(Class<?> toolClz, Object toolObj) {
        this(new BeanWrap(Solon.context(), toolClz, toolObj));
    }

    public MethodToolProvider(BeanWrap beanWrap) {
        //添加带注释的工具
        ClassEggg classEggg = EgggUtil.getClassEggg(beanWrap.clz());
        for (MethodEggg me : classEggg.getPublicMethodEgggs()) {
            //兼容 mvc 注解
            if (me.getMethod().isAnnotationPresent(ToolMapping.class)) {
                MethodFunctionTool func = new MethodFunctionTool(beanWrap, me);
                tools.add(func);
            }
        }

        //如果自己就是工具集，再添加
        if (beanWrap.raw() instanceof ToolProvider) {
            for (FunctionTool t1 : ((ToolProvider) beanWrap.raw()).getTools()) {
                tools.add(t1);
            }
        }
    }

    @Override
    public Collection<FunctionTool> getTools() {
        return tools;
    }
}