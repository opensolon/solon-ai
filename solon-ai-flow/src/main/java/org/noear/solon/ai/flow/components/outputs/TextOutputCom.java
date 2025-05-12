package org.noear.solon.ai.flow.components.outputs;

import org.noear.solon.ai.flow.components.AbstractCom;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 文本输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("TextOutput")
public class TextOutputCom extends AbstractCom {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node, null);

        System.out.println(data);
    }
}
