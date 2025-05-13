package org.noear.solon.ai.flow.components.inputs;

import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.flow.components.AbstractDataCom;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 文本输入组件
 *
 * @author noear
 * @since 3.3
 */
@Component("TextInput")
public class TextInputCom extends AbstractDataCom {
    @Override
    public Object getDataInput(FlowContext context, Node node) {
        return node.getMetaOrDefault(Attrs.META_TEXT, "");
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Object data = getDataInput(context, node);

        setDataOutput(context, node, data);
    }
}
