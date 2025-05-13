package org.noear.solon.ai.flow.events;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 节点事件
 *
 * @author noear
 * @since 3.3
 */
public class NodeEvent {
    private final FlowContext context;
    private final Node node;

    public NodeEvent(FlowContext context, Node node) {
        this.context = context;
        this.node = node;
    }

    public FlowContext getContext() {
        return context;
    }

    public Node getNode() {
        return node;
    }
}
