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

import org.noear.solon.core.exception.ConvertException;
import org.noear.solon.lang.Preview;

import java.lang.reflect.Type;

/**
 * 工具调用结果转换器
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
@FunctionalInterface
public interface ToolCallResultConverter {
    /**
     * 匹配
     *
     * @deprecated 3.4
     */
    @Deprecated
    default boolean matched(String mimeType) {
        return true;
    }

    /**
     * 转换
     *
     * @param result     结果
     * @param returnType 返回类型
     */
    String convert(Object result, Type returnType) throws ConvertException;
}