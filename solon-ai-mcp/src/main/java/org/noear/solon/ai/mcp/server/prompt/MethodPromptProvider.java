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

import org.noear.solon.ai.annotation.PromptMapping;
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
public class MethodPromptProvider implements PromptProvider {
    private final List<FunctionPrompt> prompts = new ArrayList<>();

    public MethodPromptProvider(Object toolObj) {
        this(toolObj.getClass(), toolObj);
    }

    public MethodPromptProvider(Class<?> toolClz, Object toolObj) {
        this(new BeanWrap(null, toolClz, toolObj));
    }

    public MethodPromptProvider(BeanWrap beanWrap) {
        //添加带注释的工具
        for (Method method : beanWrap.rawClz().getMethods()) {
            if (method.isAnnotationPresent(PromptMapping.class)) {
                MethodFunctionPrompt resc = new MethodFunctionPrompt(beanWrap, method);
                prompts.add(resc);
            }
        }

        //如果自己就是工具集，再添加
        if (beanWrap.raw() instanceof PromptProvider) {
            for (FunctionPrompt t1 : ((PromptProvider) beanWrap.raw()).getPrompts()) {
                prompts.add(t1);
            }
        }
    }

    @Override
    public Collection<FunctionPrompt> getPrompts() {
        return prompts;
    }
}