package org.noear.solon.ai.flow.components.inputs;

import org.noear.solon.ai.flow.components.AbstractDataCom;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 聊天输入组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatInput")
public class ChatInputCom extends AbstractDataCom {
    @Override
    public Object getDataInput(FlowContext context, Node node) {
        String input_name = getDataInputName(node);
        return Context.current().param(input_name);
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Object data = getDataInput(context, node);

        setDataOutput(context, node, data);
    }
}