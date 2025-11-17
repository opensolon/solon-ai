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

import org.noear.eggg.FieldEggg;
import org.noear.eggg.TypeEggg;
import org.noear.snack4.ONode;
import org.noear.snack4.annotation.ONodeAttrHolder;
import org.noear.snack4.codec.util.EgggDigestAddin;
import org.noear.snack4.codec.util.EgggUtil;
import org.noear.snack4.jsonschema.JsonSchema;
import org.noear.snack4.jsonschema.SchemaKeyword;
import org.noear.snack4.jsonschema.SchemaType;
import org.noear.solon.Utils;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Nullable;

import java.lang.reflect.*;
import java.util.*;

/**
 * Tool 架构工具
 *
 * @author noear
 * @author ityangs ityangs@163.com
 * @since 3.1
 * @since 3.3
 * @since 3.7
 */
public class ToolSchemaUtil {
    static {
        EgggUtil.addDigestAddin(Param.class, (EgggDigestAddin<Param>) (ce, ae, anno) -> {
            String name = Utils.annoAlias(anno.value(), anno.value());

            if (Utils.isEmpty(name)) {
                if (ae instanceof FieldEggg) {
                    name = ((FieldEggg) ae).getName();
                } else {
                    return null;
                }
            }

            return new ONodeAttrHolder(name, null, anno.description(), anno.required());
        });
    }

    private static JsonSchema jsonSchema = JsonSchema.builder().build();

    /**
     * 构建参数申明
     *
     */
    public static @Nullable ParamDesc paramOf(AnnotatedElement ae, TypeEggg typeEggg) {
        Param p1Anno = ae.getAnnotation(Param.class);
        if (p1Anno == null) {
            return null;
        }

        //断言
        //Assert.notEmpty(p1Anno.description(), "Param description cannot be empty");

        if (ae instanceof Parameter) {
            Parameter p1 = (Parameter) ae;
            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            return new ParamDesc(name, typeEggg.getGenericType(), p1Anno.required(), p1Anno.description());
        } else {
            Field p1 = (Field) ae;
            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            return new ParamDesc(name, typeEggg.getGenericType(), p1Anno.required(), p1Anno.description());
        }
    }

    /**
     * 构建输入架构
     */
    public static String buildInputSchema(List<ParamDesc> paramAry) {
        ONode rootNode = new ONode();

        ONode requiredNode = new ONode().asArray();

        rootNode.set("type", SchemaType.OBJECT);
        rootNode.getOrNew("properties").then(propertiesNode -> {
            propertiesNode.asObject(TreeMap::new);

            for (ParamDesc fp : paramAry) {
                ONode paramNode = createSchema(fp.type());
                paramNode.set(SchemaKeyword.DESCRIPTION, fp.description());
                propertiesNode.set(fp.name(), paramNode);


                if (fp.required()) {
                    requiredNode.add(fp.name());
                }
            }
        });

        if (requiredNode.getArrayUnsafe().size() > 0) {
            rootNode.set("required", requiredNode);
        }

        return rootNode.toJson();
    }

    /**
     * 构建输出架构
     */
    public static String buildOutputSchema(Type returnType) {
        return createSchema(returnType).toJson();
    }

    /**
     * 生成架构
     */
    public static ONode createSchema(Type type) {
        return jsonSchema.createGenerator(type).generate();
    }

    /**
     * 乎略输出架构
     */
    public static boolean isIgnoreOutputSchema(Type type) {
        if (type == void.class) {
            return true;
        } else if (type == String.class) {
            return true;
        } else if (type == Boolean.class) {
            return true;
        } else if (type instanceof Class) {
            Class clz = ((Class) type);

            if (Number.class.isAssignableFrom(clz)) {
                return true;
            } else if (Date.class.isAssignableFrom(clz)) {
                return true;
            }

            return clz.isPrimitive() || clz.isEnum();
        }

        return false;
    }
}