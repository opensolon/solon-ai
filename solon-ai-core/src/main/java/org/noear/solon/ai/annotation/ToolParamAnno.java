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

import org.noear.solon.Utils;
import org.noear.solon.annotation.Param;

import java.lang.annotation.Annotation;

/**
 * 工具参数注解包装
 *
 * @author noear
 * @since 3.1
 */
public class ToolParamAnno implements ToolParam {
    private String name;
    private String description;
    private boolean required;

    public ToolParamAnno(String name, String description, boolean required) {
        this.name = (name == null ? "" : name);
        this.description = (description == null ? "" : description);
        this.required = required;
    }

    public static ToolParam fromMapping(Param anno) {
        return new ToolParamAnno(Utils.annoAlias(anno.value(), anno.name()), anno.description(), anno.required());
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

    /**
     * 是否必须
     */
    @Override
    public boolean required() {
        return required;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return ToolParam.class;
    }
}