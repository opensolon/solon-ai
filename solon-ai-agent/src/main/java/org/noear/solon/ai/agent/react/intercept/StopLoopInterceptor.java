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

    public StopLoopInterceptor(int maxRepeatCount, int windowSize) {
        this.maxRepeatCount = Math.max(2, maxRepeatCount);
        this.windowSize = Math.max(4, windowSize);
    }

    public StopLoopInterceptor() {
        this(3, 8); // 稍微放大窗口，增加容错
    }

    @Override
    public void onReason(ReActTrace trace, AssistantMessage message) {
        if (message == null) return;

        String fingerprint = generateNormalizedFingerprint(message);
        if (fingerprint == null) return;

        LinkedList<String> history = trace.getExtraAs(EXTRAS_HISTORY_KEY);
        if (history == null) {
            history = new LinkedList<>();
            trace.setExtra(EXTRAS_HISTORY_KEY, history);
        }

        history.add(fingerprint);
        if (history.size() > windowSize) {
            history.removeFirst();
        }

        long count = history.stream().filter(fp -> fp.equals(fingerprint)).count();

        if (count >= maxRepeatCount) {
            // --- 核心优化点：软中断策略 ---

            // 1. 构造一个引导模型收尾的提示语，而不是直接停止
            String breakMsg = String.format(
                    "SYSTEM ALERT: Potential loop detected. You have called the same action %d times. " +
                            "Please STOP further actions and provide a Final Answer based on the information you currently have.",
                    count
            );

            log.warn("ReAct Loop detected for agent [{}], injecting break command.", trace.getAgentName());

            // 2. 将其注入为下一次的 Observation，给模型“自省”和“总结”的机会
            trace.pending(breakMsg);

            // 3. 清理该 trace 的历史，防止在收尾阶段再次触发
            history.clear();
        }
    }

    private String generateNormalizedFingerprint(AssistantMessage message) {
        if (Assert.isNotEmpty(message.getToolCalls())) {
            StringBuilder sb = new StringBuilder();
            for (ToolCall call : message.getToolCalls()) {
                // 仅对工具名+参数进行指纹化
                sb.append(call.getName()).append(":").append(ONode.serialize(call.getArguments()));
            }
            return sb.toString();
        } else if (Assert.isNotEmpty(message.getContent())) {
            String content = message.getContent();
            // 提取 Action 块，忽略 Thought 的微小差异
            int actionIdx = content.indexOf("Action:");
            if (actionIdx >= 0) {
                return content.substring(actionIdx).trim();
            }
            // 如果只有内容，取前 50 个字符作为特征，防止文本生成死循环
            return content.length() > 50 ? content.substring(0, 50) : content;
        }
        return null;
    }
}