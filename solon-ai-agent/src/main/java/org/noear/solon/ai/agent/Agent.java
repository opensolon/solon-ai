/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent;

import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;

/**
 * 智能体接口
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface Agent extends NamedTaskComponent {
    /**
     * 名字
     */
    String name();

    /**
     * 描述
     */
    String description();

    /**
     * 调用
     *
     * @param prompt 提示语
     */
    String call(FlowContext context, String prompt) throws Throwable;

    /**
     * 作为 solon-flow TaskComponent 运行（方便 solon-flow 整合）
     *
     * @param context 流上下文
     * @param node    当前节点
     */
    default void run(FlowContext context, Node node) throws Throwable {
        // 1. 状态清理（不建议在 run 里全局累加 ITERATIONS，因为这会导致并行分支计数混乱）
        context.lastNode(null);

        // 2. 执行任务
        String prompt = context.getAs(KEY_PROMPT);
        long start = System.currentTimeMillis();
        String result = call(context, prompt);
        long duration = System.currentTimeMillis() - start;

        // 3. 更新结果
        context.put(KEY_ANSWER, result);

        // 4. 记录轨迹
        String traceKey = context.getAs(KEY_CURRENT_TRACE_KEY);
        if (traceKey != null) {
            Object traceObj = context.get(traceKey);
            if (traceObj instanceof TeamTrace) {
                ((TeamTrace) traceObj).addStep(name(), result, duration);

                String history = ((TeamTrace) traceObj).getFormattedHistory();
                context.put(KEY_HISTORY, history);
            }
        }
    }

    static String KEY_PROMPT = "prompt";
    static String KEY_ANSWER = "answer";
    static String KEY_HISTORY = "history";
    static String KEY_NEXT_AGENT = "next_agent";
    static String KEY_ITERATIONS = "_total_iterations";
    static String KEY_CURRENT_TRACE_KEY = "_current_trace_key";
    static String ID_START = "start";
    static String ID_END = "end";
    static String ID_ROUTER = "router";
}