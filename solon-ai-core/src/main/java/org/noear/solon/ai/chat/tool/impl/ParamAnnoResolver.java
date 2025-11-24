package org.noear.solon.ai.chat.tool.impl;

import org.noear.eggg.TypeEggg;
import org.noear.solon.Utils;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.annotation.Param;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.function.BiFunction;

/**
 *
 * @author noear 2025/11/24 created
 *
 */
public class ParamAnnoResolver implements BiFunction<AnnotatedElement, TypeEggg, ParamDesc> {
    @Override
    public ParamDesc apply(AnnotatedElement element, TypeEggg typeEggg) {
        Param p1Anno = element.getAnnotation(Param.class);

        if (p1Anno != null) {
            if (element instanceof Parameter) {
                Parameter p1 = (Parameter) element;
                String name = Utils.annoAlias(p1Anno.name(), p1.getName());
                return new ParamDesc(name, typeEggg.getGenericType(), p1Anno.required(), p1Anno.description());
            } else {
                Field p1 = (Field) element;
                String name = Utils.annoAlias(p1Anno.name(), p1.getName());
                return new ParamDesc(name, typeEggg.getGenericType(), p1Anno.required(), p1Anno.description());
            }
        }

        return null;
    }
}
