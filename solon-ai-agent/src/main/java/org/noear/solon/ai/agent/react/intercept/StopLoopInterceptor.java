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

import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
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
public class StopLoopInterceptor extends AbsReActInterceptor {
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
    public void onThought(ReActTrace trace, String thoughtContent, AssistantMessage assistantMessage) {
        if (assistantMessage == null) return;

        String fingerprint = generateNormalizedFingerprint(assistantMessage);
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
            // --- 核心优化：软提醒 + LLM 自纠（不再 pending 挂起） ---

            String breakMsg = String.format(
                    "【系统提示：检测到潜在循环 (Loop Detected)】\n" +
                            "系统检测到您最近 %d 次（连续 %d 次相同）执行了高度相似的操作，可能存在\"原地打转\"的情况。\n\n" +
                            "请立即暂停当前操作思路，审视历史 Observation 并执行以下步骤：\n" +
                            "1. **有效性评估**：如果最近的尝试没有带来有效新线索，说明当前策略已失效，请必须更换思路或换个角度切入。\n" +
                            "2. **强制收敛**：若已有足够信息供用户参考，请直接输出 Final Answer 结束任务。\n" +
                            "3. **避免重复**：不要重复执行已经执行过且未产生新信息的操作。",
                    count, count
            );

            // 注入用户消息到工作记忆（与 auto-rethink 同一机制，参见 ReasonTask 第 111 行）
            trace.getWorkingMemory().addMessage(ChatMessage.ofUser(breakMsg));
            log.warn("ReAct Loop detected for agent [{}], injected self-correction prompt.", trace.getAgentName());

            // 清除计数，避免注入的消息本身又被算成一次循环
            history.clear();

            // 不设 session.pending(true) — 不永久挂起
            // 不设 trace.setFinalAnswer(...) — 不强行终止
            // 不改 route — 当前 tool call 正常执行
            // LLM 在下一轮 Reasoning 时会看到提醒并自纠
        }
    }

    private String generateNormalizedFingerprint(AssistantMessage message) {
        if (Assert.isNotEmpty(message.getToolCalls())) {
            // 只取工具名做指纹，忽略参数差异（避免"读不同文件"检测不到）
            StringBuilder sb = new StringBuilder();
            for (ToolCall call : message.getToolCalls()) {
                sb.append("tool:").append(call.getName());
            }
            return sb.toString();
        } else if (Assert.isNotEmpty(message.getContent())) {
            String content = message.getContent();
            // 提取 Action 块，忽略 Thought 的微小差异
            int actionIdx = content.indexOf("Action:");
            if (actionIdx >= 0) {
                String actionLine = content.substring(actionIdx).trim();
                // 取 Action: 工具名 部分，忽略参数
                int braceIdx = actionLine.indexOf("{");
                if (braceIdx >= 0) {
                    return actionLine.substring(0, braceIdx).trim();
                }
                return actionLine;
            }
            // 如果只有内容，取前 50 个字符作为特征
            return content.length() > 50 ? content.substring(0, 50) : content;
        }
        return null;
    }
}