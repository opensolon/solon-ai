package org.noear.solon.ai.flow.components;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 组件基类
 *
 * @author noear
 * @since 3.3
 */
public abstract class AbstractCom implements TaskComponent {
    /**
     * 获取组件描述
     */
    public String getDescription() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取组件输入名字（有默认，可以配置）
     */
    protected String getInputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_INPUT, Attrs.META_IO_MESSAGE);
    }

    /**
     * 获取组件输出名字（有默认，可以配置）
     */
    protected String getOutputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_OUTPUT, Attrs.META_IO_MESSAGE);
    }


    /**
     * 获取组件输入
     */
    public Object getInput(FlowContext context, Node node, Object reference) throws Throwable {
        String input_name = getInputName(node);
        return context.get(input_name);
    }

    /**
     * 获取组件输出
     */
    public void setOutput(FlowContext context, Node node, Object data, Object reference) throws Throwable {
        String output_name = getOutputName(node);
        context.put(output_name, data);
    }

    /**
     * 运行
     */
    @Override
    public abstract void run(FlowContext context, Node node) throws Throwable;
}