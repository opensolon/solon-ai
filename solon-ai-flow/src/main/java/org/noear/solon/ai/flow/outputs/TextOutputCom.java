package org.noear.solon.ai.flow.outputs;

import org.noear.solon.ai.flow.AiTaskComponent;
import org.noear.solon.ai.flow.Attrs;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * @author noear 2025/5/12 created
 */
@Component("TextOutput")
public class TextOutputCom implements AiTaskComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String input_name = node.getMetaOrDefault(Attrs.ATTR_INPUT, Attrs.ATTR_IO_DEFAULT);
        String output_name = node.getMetaOrDefault(Attrs.ATTR_OUTPUT, Attrs.ATTR_IO_DEFAULT);

        String message = context.get(input_name);
        System.out.println(message);
    }

    @Override
    public String getDescription() {
        return "";
    }
}
