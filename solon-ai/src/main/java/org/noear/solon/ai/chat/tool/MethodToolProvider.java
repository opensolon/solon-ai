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

import org.noear.solon.ai.chat.annotation.ToolMapping;

import java.lang.reflect.Method;
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
        for (Method method : toolClz.getMethods()) {
            if (method.isAnnotationPresent(ToolMapping.class)) {
                MethodFunctionTool func = new MethodFunctionTool(toolObj, method);
                tools.add(func);
            }
        }

        if (tools.size() == 0) {
            throw new IllegalArgumentException("This toolObj is not FunctionTool");
        }
    }

    @Override
    public Collection<FunctionTool> getTools() {
        return tools;
    }
}