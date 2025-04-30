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
import org.noear.solon.annotation.Mapping;

import java.lang.annotation.Annotation;

/**
 * 工具映射注解包装
 *
 * @author noear
 * @since 3.1
 */
public class ToolMappingAnno implements ToolMapping {
    private String name;
    private String description;
    private boolean returnDirect;
    private Class<? extends ToolCallResultConverter> resultConverter;

    public ToolMappingAnno(String name, String description, boolean returnDirect, Class<? extends ToolCallResultConverter> resultConverter) {
        this.name = (name == null ? "" : name);
        this.description = (description == null ? "" : description);
        this.returnDirect = returnDirect;
        this.resultConverter = (resultConverter == null ? ToolCallResultConverter.class : resultConverter);
    }

    public static ToolMapping fromMapping(Mapping mapping) {
        return new ToolMappingAnno(mapping.name(), mapping.description(), false, null);
    }

    /**
     * 名字
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 描述
     */
    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean returnDirect() {
        return returnDirect;
    }

    /**
     * 结果转换器
     */
    @Override
    public Class<? extends ToolCallResultConverter> resultConverter() {
        return resultConverter;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return ToolMapping.class;
    }
}