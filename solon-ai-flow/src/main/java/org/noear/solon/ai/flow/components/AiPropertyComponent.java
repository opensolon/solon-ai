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
package org.noear.solon.ai.flow.components;

import io.modelcontextprotocol.util.Utils;
import org.noear.solon.flow.FlowContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ai 属性组件基类
 *
 * @author noear
 * @since 3.3
 */
public interface AiPropertyComponent extends AiComponent {
    /**
     * 设置属性
     */
    default Object getProperty(FlowContext context, String name) throws Throwable {
        List temp = context.get(Attrs.CTX_PROPERTY + "_" + name);
        if (Utils.isEmpty(temp)) {
            return null;
        } else {
            return temp.get(0);
        }
    }

    default List getPropertyAll(FlowContext context, String name) throws Throwable {
        return context.get(Attrs.CTX_PROPERTY + "_" + name);
    }

    /**
     * 获取属性
     */
    default void setProperty(FlowContext context, String name, Object value) throws Throwable {
        context.put(Attrs.CTX_PROPERTY + "_" + name, new ArrayList(Arrays.asList(value)));
    }

    /**
     * 添加属性
     */
    default void addProperty(FlowContext context, String name, Object value) throws Throwable {
        context.computeIfAbsent(Attrs.CTX_PROPERTY + "_" + name, k -> new ArrayList())
                .add(value);
    }
}