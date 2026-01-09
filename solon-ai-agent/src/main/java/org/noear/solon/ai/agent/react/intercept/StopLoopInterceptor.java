/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.lang.Preview;
import java.util.Map;
import java.util.Objects;

/**
 * ReAct 逻辑死循环拦截器 (ReAct Loop Breaker)
 * <p>该拦截器用于监控模型是否在 Action 阶段陷入死胡同，即：反复尝试完全相同的工具调用。</p>
 *
 * <p><b>核心逻辑：</b></p>
 * <ul>
 * <li>1. <b>指纹提取</b>：将工具名称与输入参数组合成唯一的执行指纹。</li>
 * <li>2. <b>历史溯源</b>：在当前 {@link ReActTrace} 的历史消息中检索相同指纹的出现次数。</li>
 * <li>3. <b>硬性中断</b>：达到阈值时抛出异常。在 ReAct 闭环中，该异常信息通常会被回传给模型，触发模型的自愈能力（Self-Correction）。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class StopLoopInterceptor implements ReActInterceptor {
    /** 同一操作（工具名+参数）允许重复的最大次数 */
    private final int maxSameActions;

    /**
     * @param maxSameActions 最大允许重复次数（通常设为 2-3 次，给模型纠错的机会）
     */
    public StopLoopInterceptor(int maxSameActions) {
        this.maxSameActions = Math.max(2, maxSameActions);
    }

    /**
     * 默认构造函数（默认阈值为 3 次）
     */
    public StopLoopInterceptor() {
        this(3);
    }

    /**
     * 在工具执行动作发起前进行检测
     */
    @Override
    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        // 1. 生成当前调用的语义指纹（工具名 + 参数）
        String currentFingerprint = toolName + ":" + Objects.toString(args);

        // 2. 检索历史记录中的重复模式
        // 策略：扫描全量历史消息，统计该指纹出现的频次
        long repeatCount = trace.getMessages().stream()
                .filter(m -> m.getContent() != null && m.getContent().contains(currentFingerprint))
                .count();

        // 3. 判定死循环风险
        if (repeatCount >= maxSameActions) {
            // 抛出带有指导意义的异常信息。
            // 好的 ReAct 驱动器会捕获此异常并将其作为 Observation 反馈给 LLM，
            // 从而提示模型：“此路径已重复多次且无效，请尝试更换参数或切换工具”。
            throw new RuntimeException("Detected loop: You have tried [" + toolName + "] with same args "
                    + maxSameActions + " times. Please change your strategy, try different arguments, or stop.");
        }
    }
}