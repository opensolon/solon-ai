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

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.*;

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
        //Assert.notEmpty(p1Anno.description(), "Param description cannot be empty");

        if (ae instanceof Parameter) {
            Parameter p1 = (Parameter) ae;
            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            return new ParamDesc(name, p1.getParameterizedType(), p1Anno.required(), p1Anno.description());
        } else {
            Field p1 = (Field) ae;
            String name = Utils.annoAlias(p1Anno.name(), p1.getName());
            return new ParamDesc(name, p1.getGenericType(), p1Anno.required(), p1Anno.description());
        }
    }

    /**
     * 构建工具参数节点
     *
     * @param toolParams       工具参数
     * @param schemaParentNode 架构父节点（待构建）
     */
    public static ONode buildToolParametersNode(List<ParamDesc> toolParams, ONode schemaParentNode) {
        schemaParentNode.asObject();

        ONode requiredNode = new ONode(schemaParentNode.options()).asArray();

        schemaParentNode.set("type", TYPE_OBJECT);
        schemaParentNode.getOrNew("properties").build(propertiesNode -> {
            propertiesNode.asObject();

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
     * 主入口方法：构建 Schema 节点（递归处理）
     *
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:19
     */
    public static void buildToolParamNode(Type type, String description, ONode schemaNode) {
        if (description != null && !description.isEmpty()) {
            schemaNode.set("description", description);
        }

        //处理 ParameterizedType 类型（泛型），如 List<T>、Map<K,V>、Optional<T> 等
        if (type instanceof ParameterizedType) {
            handleParameterizedType((ParameterizedType) type, description, schemaNode);
            return;
        }

        //处理普通 Class 类型：数组、枚举、POJO 等
        if (type instanceof Class<?>) {
            handleClassType((Class<?>) type, description, schemaNode);
            return;
        }

        // 默认 fallback 为 string 类型
        schemaNode.set("type", TYPE_STRING);
    }


    /**
     * @Description 处理 ParameterizedType 类型（如 Result<T>、List<T>、Map<K,V> 等）
     * 并自动识别并解析带泛型字段的包装类（保留结构并替换泛型类型）
     * @Param type
     * @Param description
     * @Param schemaNode
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:19
     */
    private static void handleParameterizedType(ParameterizedType pt, String description, ONode schemaNode) {
        Type rawType = pt.getRawType();
        if (!(rawType instanceof Class<?>)) {
            schemaNode.set("type", TYPE_OBJECT);
            return;
        }

        Class<?> clazz = (Class<?>) rawType;

        // —— 1. 泛型集合 List<T> / Set<T> ——
        if (Collection.class.isAssignableFrom(clazz)) {
            handleGenericCollection(pt, schemaNode);
            return;
        }

        // —— 2. 泛型 Map<K,V> ——
        if (Map.class.isAssignableFrom(clazz)) {
            handleGenericMap(pt, schemaNode);
            return;
        }

        // —— 3. Optional<T> ——
        if (isOptionalType(rawType)) {
            buildToolParamNode(pt.getActualTypeArguments()[0], description, schemaNode);
            return;
        }

        // —— 4. ResponseEntity<T> ——
        if (isResponseEntityType(rawType)) {
            buildToolParamNode(pt.getActualTypeArguments()[0], description, schemaNode);
            return;
        }

        // —— 5. 泛型包装类（如 Result<T>）结构保留并解析字段泛型 ——
        TypeVariable<?>[] typeParams = clazz.getTypeParameters();
        Type[] actualTypes = pt.getActualTypeArguments();
        if (typeParams.length == actualTypes.length) {
            resolveGenericClassWithTypeArgs(clazz, actualTypes, schemaNode);
            return;
        }

        // —— fallback ——
        schemaNode.set("type", TYPE_OBJECT);
    }


    /**
     * @param clazz       原始类，如 Result
     * @param actualTypes 实际类型参数，如 T => String、List<XX> 等
     * @param schemaNode  输出的 JSON schema 节点
     * @Description 解析带泛型的类结构（如 Result<T>）：
     * - 替换字段中的泛型变量为实际类型
     * - 保留原始类的字段结构生成 schema
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:19
     */
    private static void resolveGenericClassWithTypeArgs(Class<?> clazz, Type[] actualTypes, ONode schemaNode) {
        TypeVariable<?>[] typeParams = clazz.getTypeParameters();
        Map<String, Type> typeVarMap = new HashMap<>();

        // 构造泛型变量替换映射，如 T -> List<XX>
        for (int i = 0; i < typeParams.length; i++) {
            typeVarMap.put(typeParams[i].getName(), actualTypes[i]);
        }

        schemaNode.set("type", TYPE_OBJECT);
        ONode props = schemaNode.getNew("properties");

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Type fieldType = field.getGenericType();

            // 如果字段类型是泛型变量 T，替换为实际类型
            if (fieldType instanceof TypeVariable<?> && typeVarMap.containsKey(((TypeVariable<?>) fieldType).getName())) {
                TypeVariable<?> tv = (TypeVariable<?>) fieldType;
                fieldType = typeVarMap.get(tv.getName());
            }

            // 构建字段 schema 结构
            ONode fieldSchema = new ONode();
            buildToolParamNode(fieldType, null, fieldSchema);
            props.set(field.getName(), fieldSchema);
        }
    }


    /**
     * @Description 处理普通 Class 类型：数组、枚举、POJO 等
     * @Param clazz
     * @Param description
     * @Param schemaNode
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:21
     */
    private static void handleClassType(Class<?> clazz, String description, ONode schemaNode) {
        // 数组
        if (clazz.isArray()) {
            schemaNode.set("type", TYPE_ARRAY);
            buildToolParamNode(clazz.getComponentType(), null, schemaNode.getOrNew("items"));
            return;
        }

        // Collection
        if (Collection.class.isAssignableFrom(clazz)) {
            schemaNode.set("type", TYPE_ARRAY);
            schemaNode.getOrNew("items").set("type", TYPE_OBJECT); // fallback
            return;
        }

        // Map
        if (Map.class.isAssignableFrom(clazz)) {
            schemaNode.set("type", TYPE_OBJECT);
            schemaNode.getOrNew("properties").set("type", TYPE_OBJECT); // fallback
            return;
        }

        // 枚举
        if (clazz.isEnum()) {
            handleEnumType(clazz, schemaNode);
            return;
        }

        // 特殊类型处理：日期和 URI
        if (Date.class.isAssignableFrom(clazz)) {
            schemaNode.set("type", TYPE_STRING);
            schemaNode.set("format", "date");
            return;
        }

        if (URI.class.isAssignableFrom(clazz)) {
            schemaNode.set("type", TYPE_STRING);
            schemaNode.set("format", "uri");
            return;
        }

        // 处理普通对象类型（POJO）
        handleObjectType(clazz, schemaNode);
    }

    /**
     * @Description 处理泛型集合类型：List<T>
     * @Param pt
     * @Param schemaNode
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:22
     */
    private static void handleGenericCollection(ParameterizedType pt, ONode schemaNode) {
        schemaNode.set("type", TYPE_ARRAY);
        Type[] actualTypeArguments = pt.getActualTypeArguments();
        if (actualTypeArguments.length > 0) {
            buildToolParamNode(actualTypeArguments[0], null, schemaNode.getOrNew("items"));
        }
    }


    /**
     * @Description 处理泛型 Map 类型：Map<K, V>
     * @Param pt
     * @Param schemaNode
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:22
     */
    private static void handleGenericMap(ParameterizedType pt, ONode schemaNode) {
        schemaNode.set("type", TYPE_OBJECT);
        Type[] actualTypeArguments = pt.getActualTypeArguments();
        if (actualTypeArguments.length == 2) {
            buildToolParamNode(actualTypeArguments[1], null, schemaNode.getOrNew("properties"));
        }
    }

    /**
     * @Description 处理枚举类型：设置 type 为 string，并添加 enum 值
     * @Param clazz
     * @Param schemaNode
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:22
     */
    private static void handleEnumType(Class<?> clazz, ONode schemaNode) {
        schemaNode.set("type", TYPE_STRING);
        schemaNode.getOrNew("enum").build(n -> {
            for (Object e : clazz.getEnumConstants()) {
                n.add(e.toString());
            }
        });
    }


    /**
     * @Description 处理 POJO 类型（含字段映射）
     * @Param clazz
     * @Param schemaNode
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:23
     */
    private static void handleObjectType(Class<?> clazz, ONode schemaNode) {
        String typeStr = jsonTypeOfJavaType(clazz);
        schemaNode.set("type", typeStr);

        if (!TYPE_OBJECT.equals(typeStr)) {
            return;
        }

        ONode requiredNode = new ONode(schemaNode.options()).asArray();

        schemaNode.getOrNew("properties").build(propertiesNode -> {
            propertiesNode.asObject();
            for (FieldWrap fw : ClassWrap.get(clazz).getAllFieldWraps()) {
                Field field = fw.getField();
                ParamDesc fp = paramOf(field);
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


    /**
     * @Description 判断是否为 Optional 类型
     * @Param rawType
     * @Return boolean
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:23
     */
    private static boolean isOptionalType(Type rawType) {
        return rawType.getTypeName().startsWith("java.util.Optional");
    }


    /**
     * @Description 判断是否为 ResponseEntity 类型（或你自定义的包）
     * @Param rawType
     * @Return boolean
     * @Author ityangs@163.com
     * @Date 2025/5/20 10:23
     */
    private static boolean isResponseEntityType(Type rawType) {
        return rawType.getTypeName().startsWith("org.springframework.http.ResponseEntity");
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

    /**
     * 获取原始类型
     */
    public static Class<?> getRawClass(Type type) {
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        } else if (type instanceof Class) {
            return (Class<?>) type;
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }
}