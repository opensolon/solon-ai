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

import org.noear.solon.ai.util.ParamDesc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具定制基类
 *
 * @author noear
 * @since 3.9.5
 */
public abstract class AbsTool implements FunctionTool {
    protected final List<ParamDesc> params = new ArrayList<>();
    protected Type returnType;
    protected String inputSchema;
    protected String outputSchema;

    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param type        参数类型
     * @param description 参数描述
     */
    protected void addParam(String name, Type type, String description) {
        addParam(name, type, true, description);
    }

    protected void addParam(String name, Type type, boolean required, String description) {
        addParam(name, type, required, description, null);
    }

    protected void addParam(String name, Type type, boolean required, String description, String defaultValue) {
        params.add(new ParamDesc(name, type, required, description, defaultValue));
        inputSchema = null;
    }

    @Override
    public String name() {
        return this.getClass().getSimpleName();
    }

    @Override
    public String title() {
        return name();
    }


    @Override
    public boolean returnDirect() {
        return false;
    }

    @Override
    public String inputSchema() {
        if (inputSchema == null) {
            inputSchema = ToolSchemaUtil.buildInputSchema(params);
        }

        return inputSchema;
    }

    @Override
    public String outputSchema() {
        if (outputSchema == null) {
            if (returnType != null) {
                if (ToolSchemaUtil.isIgnoreOutputSchema(returnType) == false) {
                    outputSchema = ToolSchemaUtil.buildOutputSchema(returnType);
                } else {
                    outputSchema = "";
                }
            }
        }

        return outputSchema;
    }

    @Override
    public Type returnType() {
        return returnType;
    }
}