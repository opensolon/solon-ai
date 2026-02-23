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

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 避免逻辑死循环拦截器 (Loop Breaker)
 *
 * <p>通过监控动作意图的滑动窗口频率，防止智能体陷入连续重复或 A-B-A-B 型往复迭代。</p>
 */
@Preview("3.8.1")
public class StopLoopInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StopLoopInterceptor.class);
    private static final String EXTRAS_HISTORY_KEY = "stoploop_history";

    private final int maxRepeatCount;
    private final int windowSize;

    /**
     * @param maxRepeatCount 在窗口内允许同一动作出现的次数阈值
     * @param windowSize     监控最近的 N 个动作
     */
    public StopLoopInterceptor(int maxRepeatCount, int windowSize) {
        this.maxRepeatCount = Math.max(2, maxRepeatCount);
        this.windowSize = Math.max(4, windowSize);
    }

    public StopLoopInterceptor() {
        this(3, 6);
    }

    @Override
    public void onReason(ReActTrace trace, AssistantMessage message) {
        if (message == null) return;

        // 1. 生成标准化指纹 (Arguments 排序序列化，防止 key 顺序导致的指纹失效)
        String fingerprint = generateNormalizedFingerprint(message);
        if (fingerprint == null) return;

        // 2. 使用 Trace 内部的 extras 维护滑动窗口历史
        LinkedList<String> history = trace.getExtraAs(EXTRAS_HISTORY_KEY);
        if (history == null) {
            history = new LinkedList<>();
            trace.setExtra(EXTRAS_HISTORY_KEY, history);
        }

        // 3. 判定重复频率
        history.add(fingerprint);
        if (history.size() > windowSize) {
            history.removeFirst();
        }

        // 统计当前指纹在窗口内的出现次数
        long count = history.stream().filter(fp -> fp.equals(fingerprint)).count();

        if (count >= maxRepeatCount) {
            String errorMsg = String.format(
                    "Detected ReAct loop in agent [%s]: Action intent repeated %d times in recent %d steps.",
                    trace.getAgentName(), count, history.size()
            );

            log.warn(errorMsg);

            // 触发中断逻辑
            trace.pending(errorMsg);
        }
    }

    private String generateNormalizedFingerprint(AssistantMessage message) {
        if (Assert.isNotEmpty(message.getToolCalls())) {
            StringBuilder sb = new StringBuilder("tool:");
            for (ToolCall call : message.getToolCalls()) {
                // 使用默认序列化（Snack4 默认会对 Map 的 Key 排序，确保指纹一致性）
                sb.append(call.getName()).append(ONode.serialize(call.getArguments()));
            }
            return sb.toString();
        } else if (Assert.isNotEmpty(message.getContent())) {
            String content = message.getContent();
            // 针对文本模式：只关注 Action 部分，过滤 Thought 部分的波动
            int actionIdx = content.indexOf("Action:");
            return (actionIdx >= 0) ? content.substring(actionIdx).trim() : content.trim();
        }
        return null;
    }
}