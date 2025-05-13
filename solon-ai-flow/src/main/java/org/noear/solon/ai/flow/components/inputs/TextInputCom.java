package org.noear.solon.ai.flow.components.inputs;

import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.flow.components.AiIoComponent;
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
public class TextInputCom extends AbsAiComponent implements AiIoComponent {
    @Override
    public Object getInput(FlowContext context, Node node) {
        return node.getMetaOrDefault(Attrs.META_TEXT, "");
    }

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        setOutput(context, node, data);
    }
}
