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
import org.noear.snack4.json.JsonReader;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
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
    private static final String EXTRAS_PENDING_PROMPT_KEY = "stoploop_pending_prompt";
    private static final String EXTRAS_PENDING_TURN_KEY = "stoploop_pending_turn";

    private final int maxRepeatCount;
    private final int windowSize;

    public StopLoopInterceptor(int maxRepeatCount, int windowSize) {
        this.maxRepeatCount = Math.max(2, maxRepeatCount);
        this.windowSize = Math.max(Math.max(4, windowSize), this.maxRepeatCount);
    }

    public StopLoopInterceptor() {
        this(3, 8); // 稍微放大窗口，增加容错
    }

    @Override
    public void onReasonEnd(ReActTrace trace, ChatResponse response, AssistantMessage assistantMessage, long durationMs) {
        if (!isEnabled() || assistantMessage == null) return;

        String fingerprint;
        try {
            fingerprint = generateNormalizedFingerprint(assistantMessage);
        } catch (RuntimeException e) {
            // 循环检测是辅助能力，异常时必须放行主执行链。
            log.debug("Failed to generate loop-detection fingerprint", e);
            return;
        }
        if (fingerprint == null) return;

        List<String> history = trace.getExtraAs(EXTRAS_HISTORY_KEY);
        if (history == null) {
            history = new LinkedList<>();
            trace.setExtra(EXTRAS_HISTORY_KEY, history);
        }

        history.add(fingerprint);
        if (history.size() > windowSize) {
            history.remove(0);
        }

        long count = history.stream().filter(fp -> fp.equals(fingerprint)).count();

        if (count >= maxRepeatCount) {
            String breakMsg = String.format(
                    "【系统提示：检测到潜在循环 (Loop Detected)】\n" +
                            "系统检测到最近 %d 个动作的窗口内，当前操作已出现 %d 次，可能存在\"原地打转\"的情况。\n\n" +
                            "请立即暂停当前操作思路，审视历史 Observation 并执行以下步骤：\n" +
                            "1. **有效性评估**：如果最近的尝试没有带来有效新线索，说明当前策略已失效，请必须更换思路或换个角度切入。\n" +
                            "2. **强制收敛**：若已有足够信息供用户参考，请直接输出 Final Answer 结束任务。\n" +
                            "3. **避免重复**：不要重复执行已经执行过且未产生新信息的操作。",
                    windowSize, count
            );

            // Action 尚未执行；绑定当前轮次并延后注入，避免陈旧提醒被后续 Action 消费。
            trace.setExtra(EXTRAS_PENDING_PROMPT_KEY, breakMsg);
            trace.setExtra(EXTRAS_PENDING_TURN_KEY, trace.getTurnCount());
            log.warn("ReAct Loop detected for agent [{}], queued self-correction prompt.", trace.getAgentName());

            // 清除计数，避免连续重复注入提醒。
            history.clear();
        }
    }

    @Override
    public void onActionEnd(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        String breakMsg = trace.getExtraAs(EXTRAS_PENDING_PROMPT_KEY);
        Number pendingTurn = trace.getExtraAs(EXTRAS_PENDING_TURN_KEY);
        clearPendingPrompt(trace);

        if (breakMsg != null
                && pendingTurn != null
                && pendingTurn.intValue() == trace.getTurnCount()
                && !Agent.ID_END.equals(trace.getRoute())) {
            // 正常路径下本轮 Observation 已写入；提醒作为下一轮最新指令。
            trace.getWorkingMemory().addMessage(ChatMessage.ofUser(breakMsg));
        }
    }

    @Override
    public void onAgentEnd(ReActTrace trace) {
        clearPendingPrompt(trace);
    }

    private void clearPendingPrompt(ReActTrace trace) {
        trace.getExtras().remove(EXTRAS_PENDING_PROMPT_KEY);
        trace.getExtras().remove(EXTRAS_PENDING_TURN_KEY);
    }

    private String generateNormalizedFingerprint(AssistantMessage message) {
        if (Assert.isNotEmpty(message.getToolCalls())) {
            // 工具名与规范化参数共同定义动作身份；调用 id 等协议字段不参与指纹。
            StringBuilder sb = new StringBuilder();
            for (ToolCall call : message.getToolCalls()) {
                if (call == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append("tool:").append(call.getName())
                        .append("|args:").append(canonicalArguments(call));
            }
            return sb.length() == 0 ? null : sb.toString();
        } else if (Assert.isNotEmpty(message.getContent())) {
            String content = message.getContent();
            // 文本 ReAct 只跟踪 Action；普通文本会直接结束任务，不构成动作循环。
            int actionIdx = content.indexOf("Action:");
            if (actionIdx >= 0) {
                return canonicalTextAction(content.substring(actionIdx).trim());
            }
        }
        return null;
    }

    private String canonicalArguments(ToolCall call) {
        String argumentsStr = call.getArgumentsStr();
        if (Assert.isNotEmpty(argumentsStr)) {
            String raw = argumentsStr.trim();
            try {
                ONode parsed = parseSingleObject(raw);
                if (parsed != null) {
                    return canonicalJson(parsed);
                }
            } catch (RuntimeException e) {
                // 非法、流式截断或多根参数按原文区分，不能全部退化为空 Map。
            }
            return "raw:" + raw;
        }

        Map<String, Object> arguments = call.getArguments();
        return canonicalJson(ONode.ofBean(arguments == null ? Collections.emptyMap() : arguments));
    }

    private String canonicalTextAction(String action) {
        int braceIdx = action.indexOf('{');
        if (braceIdx < 0) {
            return action;
        }

        // 与 ActionTask 保持同一语义：只采用每个对象中的 name 与 arguments。
        JsonReader reader = new JsonReader(new StringReader(action.substring(braceIdx)));
        StringBuilder buf = new StringBuilder();
        while (true) {
            ONode parsed;
            try {
                parsed = reader.readNext();
            } catch (RuntimeException e) {
                break;
            }
            if (parsed == null || !parsed.isObject()) {
                break;
            }

            if (buf.length() > 0) {
                buf.append('|');
            }
            ONode args = parsed.get("arguments");
            if (!args.isObject()) {
                args = ONode.ofBean(Collections.emptyMap());
            }
            buf.append("tool:").append(parsed.get("name").getString())
                    .append("|args:").append(canonicalJson(args));
        }
        return buf.length() == 0 ? action : buf.toString();
    }

    private ONode parseSingleObject(String raw) {
        JsonReader reader = new JsonReader(new StringReader(raw));
        ONode parsed = reader.readNext();
        if (parsed == null || !parsed.isObject() || reader.readNext() != null) {
            return null;
        }
        return parsed;
    }

    private String canonicalJson(ONode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isArray()) {
            StringBuilder buf = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) buf.append(',');
                buf.append(canonicalJson(node.get(i)));
            }
            return buf.append(']').toString();
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(node.getObject().keySet());
            Collections.sort(keys);
            StringBuilder buf = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) buf.append(',');
                String key = keys.get(i);
                buf.append(ONode.ofBean(key).toJson()).append(':').append(canonicalJson(node.get(key)));
            }
            return buf.append('}').toString();
        }
        return node.toJson();
    }
}