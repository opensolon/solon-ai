package org.noear.solon.ai.flow.components.inputs;

import org.noear.solon.ai.flow.components.AbstractCom;
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
public class ChatInputCom extends AbstractCom {

    @Override
    public Object getInput(FlowContext context, Node node, Object reference) {
        String input_name = getInputName(node);
        return ((Context) reference).param(input_name);
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node, Context.current());

        setOutput(context, node, data, null);
    }
}