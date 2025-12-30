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
    static String KEY_PROMPT = "prompt";
    static String KEY_ANSWER = "answer";
    static String KEY_HISTORY = "history";
    static String KEY_NEXT_AGENT = "next_agent";
    static String KEY_CURRENT_RECORD_KEY = "_current_record_key";

    /**
     * 名字
     */
    String name();

    /**
     * 询问
     *
     * @param prompt 提示语
     */
    String ask(FlowContext context, String prompt) throws Throwable;

    /**
     * 作为 solon-flow TaskComponent 运行（方便 solon-flow 整合）
     *
     * @param context 流上下文
     * @param node    当前节点
     */
    default void run(FlowContext context, Node node) throws Throwable {
        // 1. 获取输入（优先使用初始 prompt，如果需要链式接力可以根据业务逻辑调整）
        String prompt = context.getAs(KEY_PROMPT);

        // 2. 执行具体的 Agent 逻辑
        String result = ask(context, prompt);

        // 3. 结果入库（更新当前节点的答案）
        context.put(KEY_ANSWER, result);

        // 4. 协作历史累加：这是 AgentRouterTask 能够看到进展的关键
        StringBuilder history = new StringBuilder(context.getOrDefault(KEY_HISTORY, ""));
        history.append("\n\n### ").append(name()).append(" Role Output:\n")
                .append(result);

        context.put(KEY_HISTORY, history.toString());
    }
}