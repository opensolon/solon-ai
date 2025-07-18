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
package org.noear.solon.ai.annotation;

import org.noear.solon.ai.chat.tool.ToolCallResultConverter;
import org.noear.solon.ai.chat.tool.ToolCallResultConverterDefault;

import java.lang.annotation.*;

/**
 * 工具映射
 *
 * @author noear
 * @since 3.1
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolMapping {
    /**
     * 名字
     */
    String name() default "";

    /**
     * 描述
     */
    String description();

    /**
     * 是否直接返回给调用者
     */
    boolean returnDirect() default false;

    /**
     * 结果转换器
     */
    Class<? extends ToolCallResultConverter> resultConverter() default ToolCallResultConverterDefault.class;
}