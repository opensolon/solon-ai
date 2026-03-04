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
import org.noear.snack4.jsonschema.generate.TypeMapper;
import org.noear.snack4.jsonschema.generate.TypePatternMapper;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.impl.BodyAnnoDetector;
import org.noear.solon.ai.chat.tool.impl.ParamAnnoResolver;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.Constants;
import org.noear.solon.lang.Nullable;
import org.reactivestreams.Publisher;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Predicate;

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
    private static List<Predicate<AnnotatedElement>> bodyDetectors = new CopyOnWriteArrayList<>();
    private static List<BiFunction<AnnotatedElement, TypeEggg, ParamDesc>> paramResolvers = new CopyOnWriteArrayList<>();

    private static JsonSchema jsonSchema = JsonSchema.builder().build();

    static {
        EgggUtil.addDigestAddin(Param.class, (EgggDigestAddin<Param>) (ce, ae, anno) -> {
            String name = Utils.annoAlias(anno.value(), anno.name());

            if (Utils.isEmpty(name)) {
                if (ae instanceof FieldEggg) {
                    name = ((FieldEggg) ae).getName();
                } else {
                    return null;
                }
            }

            String defVal = (Constants.PARM_UNDEFINED_VALUE.equals(anno.defaultValue()) ? null : anno.defaultValue());

            return new ONodeAttrHolder(name, null, anno.description(), anno.required(), defVal, ae);
        });

        bodyDetectors.add(new BodyAnnoDetector());
        paramResolvers.add(new ParamAnnoResolver());
        jsonSchema.addTypeMapper(new TypePatternMapper<Publisher>() {
            @Override
            public boolean supports(TypeEggg typeEggg) {
                return Publisher.class.isAssignableFrom(typeEggg.getType());
            }

            @Override
            public TypeEggg mapType(TypeEggg typeEggg) {
                return EgggUtil.getTypeEggg(typeEggg.getActualTypeArguments()[0]);
            }
        });
    }

    /**
     * 添加主体注解探测器
     */
    public static void addBodyDetector(Predicate<AnnotatedElement> detector) {
        bodyDetectors.add(detector);
    }

    /**
     * 添加参数注解分析器
     */
    public static void addParamResolver(BiFunction<AnnotatedElement, TypeEggg, ParamDesc> resolver) {
        paramResolvers.add(resolver);
    }

    /**
     * 添加节点描述处理
     */
    public static void addNodeDescribe(Class<? extends Annotation> annoType, EgggDigestAddin digestAddin) {
        EgggUtil.addDigestAddin(annoType, digestAddin);
    }

    /**
     * 检测是否有 body 注解
     */
    private static boolean hasBodyAnno(AnnotatedElement element) {
        for (Predicate<AnnotatedElement> d1 : bodyDetectors) {
            if (d1.test(element)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取参数描声明（可能为 null）
     */
    private static @Nullable ParamDesc getParamDesc(AnnotatedElement element, TypeEggg typeEggg) {
        for (BiFunction<AnnotatedElement, TypeEggg, ParamDesc> r1 : paramResolvers) {
            ParamDesc pd = r1.apply(element, typeEggg);
            if (pd != null) {
                return pd;
            }
        }

        return null;
    }

    /**
     * 构建参数申明（支持 @Param 和 @Body 注解）
     */
    public static @Nullable Map<String, ParamDesc> buildInputParams(AnnotatedElement ae, TypeEggg typeEggg) {
        ParamDesc pd1 = getParamDesc(ae, typeEggg);

        if (pd1 != null) {
            return Collections.singletonMap(pd1.name(), pd1);
        } else {
            Map<String, ParamDesc> paramMap = new LinkedHashMap<>();

            if (hasBodyAnno(ae)) {
                for (FieldEggg fg1 : typeEggg.getClassEggg().getAllFieldEgggs()) {
                    pd1 = getParamDesc(fg1.getField(), fg1.getTypeEggg());

                    if (pd1 == null) {
                        pd1 = new ParamDesc(fg1.getAlias(), fg1.getGenericType(), false, "", null);
                    }

                    paramMap.put(pd1.name(), pd1);
                }
            }

            return paramMap;
        }
    }

    /**
     * 构建输入架构
     */
    public static String buildInputSchema(Collection<ParamDesc> paramAry) {
        ONode rootNode = new ONode();

        ONode requiredNode = new ONode().asArray();

        rootNode.set("type", SchemaType.OBJECT);
        rootNode.getOrNew("properties").then(propertiesNode -> {
            propertiesNode.asObject(TreeMap::new);

            for (ParamDesc fp : paramAry) {
                ONode paramNode = createSchema(fp.type());
                paramNode.set(SchemaKeyword.DESCRIPTION, fp.description());

                if (Utils.isNotEmpty(fp.defaultValue())) {
                    Object defVal = ONode.ofBean(fp.defaultValue())
                            .toBean(fp.type());
                    paramNode.set(SchemaKeyword.DEFAULT, defVal);
                }

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
        if (type instanceof ParameterizedType) {
            TypeEggg typeEggg = EgggUtil.getTypeEggg(type);
            TypeMapper typeMapper = jsonSchema.getTypeMapper(typeEggg);

            if (typeMapper != null) {
                typeEggg = typeMapper.mapType(typeEggg);
                type = typeEggg.getGenericType();
            }
        }

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

    /**
     * 工具结果转换
     */
    public static String resultConvert(FunctionTool fun, Object result){
        if(result == null){
            return null;
        }

        if(result instanceof String){
            return (String)result;
        }

        Type type = fun.returnType();
        if(type == null){
            type= result.getClass();
        }

        return fun.resultConverter().convert(result, type);
    }
}