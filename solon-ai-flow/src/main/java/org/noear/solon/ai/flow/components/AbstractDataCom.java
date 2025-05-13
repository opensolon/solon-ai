package org.noear.solon.ai.flow.components;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 数据组件基类
 *
 * @author noear
 * @since 3.3
 */
public abstract class AbstractDataCom implements TaskComponent {
    /**
     * 获取组件描述
     */
    public String getDescription() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取组件数据输入名字（有默认，可以配置）
     */
    protected String getDataInputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_DATA_INPUT, Attrs.META_DATA_IO_MESSAGE);
    }

    /**
     * 获取组件数据输出名字（有默认，可以配置）
     */
    protected String getDataOutputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_DATA_OUTPUT, Attrs.META_DATA_IO_MESSAGE);
    }


    /**
     * 获取组件数据输入
     */
    public Object getDataInput(FlowContext context, Node node, Object reference) throws Throwable {
        String input_name = getDataInputName(node);
        return context.get(input_name);
    }

    /**
     * 获取组件数据输出
     */
    public void setDataOutput(FlowContext context, Node node, Object data, Object reference) throws Throwable {
        String output_name = getDataOutputName(node);
        context.put(output_name, data);
    }

    /**
     * 运行
     */
    @Override
    public abstract void run(FlowContext context, Node node) throws Throwable;
}