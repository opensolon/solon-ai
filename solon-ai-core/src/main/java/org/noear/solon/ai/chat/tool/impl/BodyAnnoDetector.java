package org.noear.solon.ai.chat.tool.impl;

import org.noear.solon.annotation.Body;

import java.lang.reflect.AnnotatedElement;
import java.util.function.Predicate;

/**
 * Body 注解检测
 * @author noear
 * @since 3.7
 */
public class BodyAnnoDetector implements Predicate<AnnotatedElement> {
    @Override
    public boolean test(AnnotatedElement element) {
        return element.isAnnotationPresent(Body.class);
    }
}
