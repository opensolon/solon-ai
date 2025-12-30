package org.noear.solon.ai.agent.react;

import org.noear.solon.flow.intercept.FlowInterceptor;

import java.util.Map;

/**
 * ReAct 过程监听器
 */
public interface ReActInterceptor extends FlowInterceptor {
    /**
     * 思考时触发
     */
    default void onThought(ReActRecord record, String thought) {
    }

    /**
     * 调用工具前触发
     */
    default void onAction(ReActRecord record, String toolName, Map<String, Object> args) {
    }

    /**
     * 工具返回结果后触发
     */
    default void onObservation(ReActRecord record, String result) {
    }


    /**
     * 创建一个默认的拦截器
     */
    static SimpleReActInterceptor.Builder builder() {
        return new SimpleReActInterceptor.Builder();
    }
}