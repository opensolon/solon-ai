package org.noear.solon.ai.flow.components;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 输入输出组件基类
 *
 * @author noear
 * @since 3.3
 */
public interface AiIoComponent extends AiComponent {
    /**
     * 获取组件数据输入名字（有默认，可以配置）
     */
    default String getInputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_INPUT, Attrs.META_IO_MESSAGE);
    }

    /**
     * 获取组件数据输出名字（有默认，可以配置）
     */
    default String getOutputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_OUTPUT, Attrs.META_IO_MESSAGE);
    }


    /**
     * 获取组件数据输入
     */
    default Object getInput(FlowContext context, Node node) throws Throwable {
        String input_name = getInputName(node);
        return context.get(input_name);
    }

    /**
     * 获取组件数据输出
     */
    default void setOutput(FlowContext context, Node node, Object data) throws Throwable {
        String output_name = getOutputName(node);
        context.put(output_name, data);
    }
}