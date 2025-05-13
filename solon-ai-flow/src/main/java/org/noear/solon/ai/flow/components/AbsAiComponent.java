package org.noear.solon.ai.flow.components;

import org.noear.solon.ai.flow.events.Events;
import org.noear.solon.ai.flow.events.NodeEvent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * @author noear
 * @since 3.3
 */
public abstract class AbsAiComponent implements AiComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        context.eventBus().send(Events.EVENT_FLOW_NODE_START, new NodeEvent(context, node));
        doRun(context, node);
        context.eventBus().send(Events.EVENT_FLOW_NODE_END, new NodeEvent(context, node));
    }

    protected abstract void doRun(FlowContext context, Node node) throws Throwable;
}
