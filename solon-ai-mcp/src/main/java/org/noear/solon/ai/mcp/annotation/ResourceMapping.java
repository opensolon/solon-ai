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
package org.noear.solon.ai.mcp.annotation;

import org.noear.solon.ai.chat.tool.ToolCallResultConverter;

import java.lang.annotation.*;

/**
 * 资源映射
 *
 * @author noear
 * @since 3.1
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResourceMapping {
    /**
     * 资源地址描述
     */
    String uri();

    /**
     * 名字
     */
    String name() default "";

    /**
     * 描述
     */
    String description() default "";

    /**
     * 结果转换器
     */
    Class<? extends ToolCallResultConverter> resultConverter() default ToolCallResultConverter.class;
}