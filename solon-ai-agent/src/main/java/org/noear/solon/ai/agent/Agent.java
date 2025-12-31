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
    static String KEY_PROMPT = "prompt";
    static String KEY_ANSWER = "answer";
    static String KEY_HISTORY = "history";
    static String KEY_NEXT_AGENT = "next_agent";
    static String KEY_ITERATIONS = "_total_iterations";
    static String KEY_CURRENT_TRACE_KEY = "_current_trace_key";

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
        // 1. 状态清理与计数
        context.lastNode(null);
        int iters = context.getOrDefault(KEY_ITERATIONS, 0);
        context.put(KEY_ITERATIONS, iters + 1);

        // 2. 执行任务
        String prompt = context.getAs(KEY_PROMPT);
        long start = System.currentTimeMillis();
        String result = ask(context, prompt);
        long duration = System.currentTimeMillis() - start;

        // 3. 更新结果
        context.put(KEY_ANSWER, result);

        // 4. 记录宏观协作历史 (用于 Supervisor 决策)
        StringBuilder history = new StringBuilder(context.getOrDefault(KEY_HISTORY, ""));
        history.append("\n[").append(name()).append("]: ").append(result);
        context.put(KEY_HISTORY, history.toString());

        // 5. 【新增】记录到结构化轨迹 (TeamTrace)
        String traceKey = context.getAs(KEY_CURRENT_TRACE_KEY);
        Object traceObj = context.get(traceKey);
        if (traceObj instanceof TeamTrace) {
            ((TeamTrace) traceObj).addStep(name(), result, duration);
        }
    }
}