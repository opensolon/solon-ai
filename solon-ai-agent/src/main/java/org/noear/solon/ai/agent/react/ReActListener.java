package org.noear.solon.ai.agent.react;

import org.noear.solon.flow.FlowContext;

import java.util.Map;

/**
 * ReAct 过程监听器
 */
public interface ReActListener {
    /**
     * 思考时触发
     */
    default void onThought(FlowContext context, ReActRecord record, String thought) {
    }

    /**
     * 调用工具前触发
     */
    default void onAction(FlowContext context, ReActRecord record, String toolName, Map<String, Object> args) {
    }

    /**
     * 工具返回结果后触发
     */
    default void onObservation(FlowContext context, ReActRecord record, String result) {
    }
}