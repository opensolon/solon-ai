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
import org.noear.solon.ai.chat.prompt.Prompt;
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
     * 针对当前任务进行初步评估或竞标（用于合同网协议等决策场景）
     *
     * @param context 流上下文
     * @param prompt  当前任务提示词
     * @return 评估结果或竞标方案（默认返回 description）
     */
    default String estimate(FlowContext context, Prompt prompt) {
        // 默认实现：如果 Agent 不支持评估，则返回静态描述
        return description();
    }

    String call(FlowContext context, Prompt prompt) throws Throwable;

    default String call(FlowContext context, String prompt) throws Throwable {
        if (prompt == null) {
            return call(context, (Prompt) null);
        } else {
            return call(context, Prompt.of(prompt));
        }
    }

    default String call(FlowContext context) throws Throwable {
        return call(context, (Prompt) null);
    }

    /**
     * 作为 solon-flow TaskComponent 运行（方便 solon-flow 整合）
     *
     * @param context 流上下文
     * @param node    当前节点
     */
    default void run(FlowContext context, Node node) throws Throwable {
        context.lastNode(null);

        Prompt originalPrompt = context.getAs(KEY_PROMPT);
        String traceKey = context.getAs(KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;

        Prompt effectivePrompt = originalPrompt;
        if (trace != null && trace.getStepCount() > 0) {
            String fullHistory = trace.getFormattedHistory();
            String newContent = "Current Task: " + originalPrompt.getUserContent() +
                    "\n\nCollaboration Progress so far:\n" + fullHistory +
                    "\n\nPlease continue based on the progress above.";
            effectivePrompt = Prompt.of(newContent);
        }

        long start = System.currentTimeMillis();
        String result = call(context, effectivePrompt);
        long duration = System.currentTimeMillis() - start;

        if (result == null) {
            result = "";
        }

        if (trace != null) {
            String stepContent = result.trim().isEmpty() ?
                    "No valid output is produced" : result;
            trace.addStep(name(), stepContent, duration);
        }
    }

    static String KEY_PROMPT = "prompt";
    static String KEY_CURRENT_TRACE_KEY = "_current_trace_key";

    static String ID_START = "start";
    static String ID_END = "end";
    static String ID_ROUTER = "router";
    static String ID_BIDDING = "bidding";
    static String ID_REASON = "reason";
    static String ID_ACTION = "action";
}