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

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.util.ParamDesc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 函数工具描述（相当于构建器）
 *
 * @author noear
 * @since 3.1
 */
public class FunctionToolDesc implements FunctionTool {
    static final Logger log = LoggerFactory.getLogger(FunctionToolDesc.class);

    private final String name;
    private String title;
    private String description;

    private final List<ParamDesc> params = new ArrayList<>();
    private Type returnType;
    private boolean returnDirect = false;
    private ToolHandler doHandler;
    private String inputSchema;
    private String outputSchema;

    public FunctionToolDesc(String name, String title, String description, Boolean returnDirect, String inputSchema, String outputSchema, ToolHandler handler) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.doHandler = handler;
        this.returnDirect = (returnDirect == null ? false : returnDirect);
    }

    /**
     * @param name 函数名字
     */
    public FunctionToolDesc(String name) {
        this.name = name;
    }

    /**
     * 申明函数标题
     *
     * @param title 参数
     */
    public FunctionToolDesc title(String title) {
        this.title = title;
        return this;
    }

    /**
     * 申明函数描述
     *
     * @param description 参数
     */
    public FunctionToolDesc description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 申明返回类型
     *
     * @param returnType 直接类型
     */
    public FunctionToolDesc returnType(Type returnType) {
        this.returnType = returnType;
        outputSchema = null;
        return this;
    }

    /**
     * 申明直接返回给调用者
     *
     * @param returnDirect 直接返回
     */
    public FunctionToolDesc returnDirect(boolean returnDirect) {
        this.returnDirect = returnDirect;
        return this;
    }

    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param type        参数类型
     * @param description 参数描述
     */
    public FunctionToolDesc paramAdd(String name, Type type, String description) {
        return paramAdd(name, type, true, description);
    }

    /**
     * 申明函数参数
     *
     * @param name        参数名字
     * @param type        参数类型
     * @param required    是否必须
     * @param description 参数描述
     */
    public FunctionToolDesc paramAdd(String name, Type type, boolean required, String description) {
        params.add(new ParamDesc(name, type, required, description));
        inputSchema = null;
        return this;
    }

    /**
     * 申明函数字符串参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc stringParamAdd(String name, String description) {
        return paramAdd(name, String.class, description);
    }

    /**
     * 申明函数整型参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc intParamAdd(String name, String description) {
        return paramAdd(name, int.class, description);
    }

    /**
     * 申明函数浮点数参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc floatParamAdd(String name, String description) {
        return paramAdd(name, float.class, description);
    }

    /**
     * 申明函数布尔参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc boolParamAdd(String name, String description) {
        return paramAdd(name, Boolean.class, description);
    }

    /**
     * 申明函数时间参数
     *
     * @param name        参数名字
     * @param description 参数描述
     */
    public FunctionToolDesc dateParamAdd(String name, String description) {
        return paramAdd(name, Date.class, description);
    }


    /**
     * 申明函数处理
     *
     * @param handler 处理器
     */
    public FunctionToolDesc doHandle(ToolHandler handler) {
        this.doHandler = handler;
        return this;
    }

    /// /////////////////////

    /**
     * 函数名字
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 函数标题
     */
    @Override
    public String title() {
        return title;
    }

    /**
     * 函数描述
     */
    @Override
    public String description() {
        return description;
    }

    /**
     * 是否直接返回给调用者
     */
    @Override
    public boolean returnDirect() {
        return returnDirect;
    }

    /**
     * 输入架构
     */
    @Override
    public String inputSchema() {
        if (inputSchema == null) {
            inputSchema = ToolSchemaUtil.buildToolParametersNode(params, new ONode())
                    .toJson();
        }

        return inputSchema;
    }

    @Override
    public String outputSchema() {
        if (outputSchema == null) {
            if (returnType != null) {
                ONode outputSchemaNode = new ONode();

                if (ToolSchemaUtil.isIgnoreOutputSchema(returnType) == false) {
                    ToolSchemaUtil.buildToolParamNode(returnType, "", outputSchemaNode);
                    outputSchema = outputSchemaNode.toJson();
                } else {
                    outputSchema = "";
                }
            }
        }

        return outputSchema;
    }

    /**
     * 执行处理
     */
    @Override
    public String handle(Map<String, Object> args) throws Throwable {
        try {
            return doHandle(args);
        } catch (Throwable ex) {
            //解包
            ex = Utils.throwableUnwrap(ex);

            if (log.isWarnEnabled()) {
                log.warn("Tool handle error, name: '{}'", name, ex);
            }
            throw ex;
        }
    }

    private String doHandle(Map<String, Object> args) throws Throwable {
        if (params.size() > 0) {
            Map<String, Object> argsNew = new HashMap<>();

            ONode argsNode = ONode.load(args);
            for (ParamDesc p1 : this.params) {
                ONode v1 = argsNode.getOrNull(p1.name());
                if (v1 == null) {
                    //null
                    argsNew.put(p1.name(), null);
                } else {
                    //用 ONode 可以自动转换类型
                    argsNew.put(p1.name(), v1.toObject(p1.type()));
                }
            }

            return doHandler.handle(argsNew);
        } else {
            return doHandler.handle(args);
        }
    }

    @Override
    public String toString() {
        return "FunctionToolDesc{" +
                "name='" + name + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", returnDirect=" + returnDirect +
                ", inputSchema=" + inputSchema() +
                ", outputSchema=" + outputSchema() +
                '}';
    }
}