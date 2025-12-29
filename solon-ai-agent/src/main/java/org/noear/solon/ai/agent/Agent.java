package org.noear.solon.ai.agent;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 智能体
 *
 * @author noear
 * @since 3.8.1
 */
public interface Agent extends TaskComponent {
    /**
     * 运行
     *
     * @param prompt 提示语
     */
    String run(String prompt) throws Throwable;

    /**
     * 作为 solon-flow TaskComponent 运行（方便 solon-flow 整合）
     *
     * @param context 流上下文
     * @param node    当前节点
     */
    default void run(FlowContext context, Node node) throws Throwable {
        run(context.getAs("prompt"));
    }
}