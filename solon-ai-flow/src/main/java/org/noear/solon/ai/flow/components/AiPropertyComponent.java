package org.noear.solon.ai.flow.components;

import org.noear.solon.flow.FlowContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 属性组件基类
 *
 * @author noear
 * @since 3.3
 */
public interface AiPropertyComponent extends AiComponent {
    /**
     * 设置属性
     */
    default List getProperty(FlowContext context, String name) throws Throwable {
        return context.get(Attrs.CTX_PROPERTY + "_" + name);
    }

    /**
     * 获取属性
     */
    default void setProperty(FlowContext context, String name, Object value) throws Throwable {
        context.put(Attrs.CTX_PROPERTY + "_" + name, Arrays.asList(value));
    }

    /**
     * 添加属性
     */
    default void addProperty(FlowContext context, String name, Object value) throws Throwable {
        context.computeIfAbsent(Attrs.CTX_PROPERTY + "_" + name, k -> new ArrayList())
                .add(value);
    }
}