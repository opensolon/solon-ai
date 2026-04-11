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
import java.util.Map;
import java.util.function.Consumer;

/**
 * 方法构建的工具提供者
 *
 * @author noear
 * @since 3.1
 */
public class MethodToolProvider implements ToolProvider {
    private final BeanWrap beanWrap;

    private List<FunctionTool> tools;

    private Map<String, Object> binding;
    private boolean includeProvider = true;

    public MethodToolProvider(Object toolObj) {
        this(toolObj.getClass(), toolObj);
    }

    public MethodToolProvider(Class<?> toolClz, Object toolObj) {
        this(new BeanWrap(Solon.context(), toolClz, toolObj));
    }

    public MethodToolProvider(BeanWrap beanWrap) {
        this.beanWrap = beanWrap;
    }

    /**
     * 绑定模板变量（即：如果描述里使用了 SnEL 模型）
     */
    public MethodToolProvider binding(Map<String, Object> binding) {
        this.binding = binding;
        return this;
    }

    /**
     * 包括接口提供（即：如果自身实现了 ToolProvider）
     */
    public MethodToolProvider includeProvide(boolean includeProvider) {
        this.includeProvider = includeProvider;
        return this;
    }

    public MethodToolProvider then(Consumer<MethodToolProvider> consumer) {
        consumer.accept(this);
        return this;
    }

    @Override
    public Collection<FunctionTool> getTools() {
        if (tools == null) {
            tools = new ArrayList<>();
            //添加带注释的工具
            ClassEggg classEggg = EgggUtil.getClassEggg(beanWrap.clz());
            for (MethodEggg me : classEggg.getPublicMethodEgggs()) {
                if (me.getMethod().isAnnotationPresent(ToolMapping.class)) {
                    MethodFunctionTool func = new MethodFunctionTool(beanWrap, me, binding);
                    tools.add(func);
                }
            }

            //如果自己就是工具集，再添加
            if (includeProvider) {
                if (beanWrap.raw() instanceof ToolProvider) {
                    for (FunctionTool t1 : ((ToolProvider) beanWrap.raw()).getTools()) {
                        tools.add(t1);
                    }
                }
            }
        }

        return tools;
    }
}