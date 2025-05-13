package org.noear.solon.ai.flow.components.outputs;

import org.noear.solon.ai.flow.components.AbstractDataCom;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 聊天输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatOutput")
public class ChatOutputCom extends AbstractDataCom {

    @Override
    public void setDataOutput(FlowContext context, Node node, Object data, Object reference) throws Throwable {
        if (data instanceof String) {
            ((Context) reference).output((String) data);
        } else {
            ((Context) reference).render(data);
        }
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Object data = getDataInput(context, node, null);

        setDataOutput(context, node, data, Context.current());
    }
}