package org.noear.solon.ai.flow.events;

/**
 * 事件名
 *
 * @author noear
 * @since 3.3
 */
public interface Events {
    /**
     * 节点开始事件
     */
    String EVENT_FLOW_NODE_START = "flow.node.start";
    /**
     * 节点结束事件
     */
    String EVENT_FLOW_NODE_END = "flow.node.end";
}
