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
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.wrap.ClassWrap;
import org.noear.solon.core.wrap.FieldWrap;
import org.noear.solon.lang.Nullable;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.Date;
import java.util.List;

/**
 * Tool 架构工具
 *
 * @author noear
 * @since 3.1
 */
public class ToolSchemaUtil {
    public static final String TYPE_OBJECT = "object";
    public static final String TYPE_ARRAY = "array";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_NULL = "null";

    public static @Nullable ParamDesc paramOf(AnnotatedElement ae) {
        Param p1Anno = ae.getAnnotation(Param.class);
        if (p1Anno == null) {
            return null;
        }

        //断言
        Assert.notEmpty(p1Anno.description(), "Param description cannot be empty");

        if (ae instanceof Parameter) {
            Parameter p1 = (Parameter) ae;
            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            return new ParamDesc(name, p1.getType(), p1Anno.required(), p1Anno.description());
        } else {
            Field p1 = (Field) ae;
            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            return new ParamDesc(name, p1.getType(), p1Anno.required(), p1Anno.description());
        }
    }

    /**
     * 构建工具参数节点
     *
     * @param toolParams       工具参数
     * @param schemaParentNode 架构父节点（待构建）
     */
    public static ONode buildToolParametersNode(List<ParamDesc> toolParams, ONode schemaParentNode) {
        ONode requiredNode = new ONode(schemaParentNode.options()).asArray();

        schemaParentNode.set("type", TYPE_OBJECT);
        schemaParentNode.getOrNew("properties").build(propertiesNode -> {
            for (ParamDesc fp : toolParams) {
                propertiesNode.getOrNew(fp.name()).build(paramNode -> {
                    buildToolParamNode(fp.type(), fp.description(), paramNode);
                });

                if (fp.required()) {
                    requiredNode.add(fp.name());
                }
            }
        });

        schemaParentNode.set("required", requiredNode);

        return schemaParentNode;
    }

    /**
     * 构建工具参数节点
     *
     * @param type        类型
     * @param description 描述
     * @param schemaNode  架构节点
     */
    public static void buildToolParamNode(Class<?> type, String description, ONode schemaNode) {
        if (type.isArray()) {
            //数组
            schemaNode.set("type", TYPE_ARRAY);

            Class<?> itemType = type.getComponentType();
            ONode typeItemSchemaNode = schemaNode.getOrNew("items");
            buildToolParamNode(itemType, null, typeItemSchemaNode);
        } else if (type.isEnum()) {
            //枚举
            schemaNode.set("type", TYPE_STRING);

            schemaNode.getOrNew("enum").build(n7 -> {
                for (Object e : type.getEnumConstants()) {
                    n7.add(e.toString());
                }
            });
        } else if (Date.class.isAssignableFrom(type)) {
            //日期
            schemaNode.set("type", TYPE_STRING);
            schemaNode.set("format", "date");
        } else if (URI.class.isAssignableFrom(type)) {
            //URI
            schemaNode.set("type", TYPE_STRING);
            schemaNode.set("format", "uri");
        } else {
            //其它
            String typeStr = jsonTypeOfJavaType(type);
            schemaNode.set("type", typeStr);

            if (TYPE_OBJECT.equals(typeStr)) {
                ONode requiredNode = new ONode(schemaNode.options()).asArray();
                schemaNode.getOrNew("properties").build(propertiesNode -> {
                    for (FieldWrap fw : ClassWrap.get(type).getAllFieldWraps()) {
                        ParamDesc fp = paramOf(fw.getField());

                        if (fp != null) {
                            propertiesNode.getOrNew(fp.name()).build(paramNode -> {
                                buildToolParamNode(fp.type(), fp.description(), paramNode);
                            });

                            if (fp.required()) {
                                requiredNode.add(fp.name());
                            }
                        }
                    }
                });

                schemaNode.set("required", requiredNode);
            }
        }

        if (description != null) {
            schemaNode.set("description", description);
        }
    }

    /**
     * json 类型转换
     */
    public static String jsonTypeOfJavaType(Class<?> type) {
        if (type.equals(String.class) || type.equals(Date.class) || type.equals(BigDecimal.class) || type.equals(BigInteger.class)) {
            return TYPE_STRING;
        } else if (type.equals(Short.class) || type.equals(short.class) || type.equals(Integer.class) || type.equals(int.class) || type.equals(Long.class) || type.equals(long.class)) {
            return TYPE_INTEGER;
        } else if (type.equals(Double.class) || type.equals(double.class) || type.equals(Float.class) || type.equals(float.class) || type.equals(Number.class)) {
            return TYPE_NUMBER;
        } else if (type.equals(Boolean.class) || type.equals(boolean.class)) {
            return TYPE_BOOLEAN;
        } else {
            return TYPE_OBJECT;
        }
    }
}