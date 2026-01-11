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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A2A (Agent to Agent) 协作协议
 * 仿 Google A2A 模式，通过 Instruction (指令) 和 State (结构化状态) 实现任务衔接
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class A2AProtocol_H extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(A2AProtocol_H.class);

    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_INSTRUCTION = "last_instruction";
    private static final String KEY_GLOBAL_STATE = "global_state";
    private static final String KEY_TRANSFER_HISTORY = "transfer_history";

    /**
     * A2A 状态管理内部类，负责 JSON 数据的深度合并
     */
    public static class A2AState {
        private final Map<String, Object> data = new LinkedHashMap<>();

        public void merge(String json) {
            if (Utils.isEmpty(json)) return;
            try {
                ONode node = ONode.ofJson(json);
                if (node.isObject()) {
                    node.getObjectUnsafe().forEach((k, v) -> data.put(k, v.toBean()));
                }
            } catch (Exception e) {
                data.put("_last_raw_error_state", json);
            }
        }

        public boolean isEmpty() {
            return data.isEmpty();
        }

        @Override
        public String toString() {
            return ONode.serialize(data);
        }
    }

    private boolean enableLoopDetection = true;

    public A2AProtocol_H(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = config.getLocale();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(Agent::name)
                .collect(Collectors.joining(", "));

        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_TRANSFER);

        if (isZh) {
            toolDesc.title("移交任务")
                    .description("当任务需要其他专家介入时调用。必须提供明确的下一步指令，并更新结构化状态。")
                    .stringParamAdd("target", "目标专家，可选: [" + expertList + "]")
                    .stringParamAdd("instruction", "给接棒专家的具体动作指令")
                    .stringParamAdd("state", "更新后的结构化状态(JSON)，用于传递关键参数、ID等");
        } else {
            toolDesc.title("Transfer Task")
                    .description("Call when another expert is needed. Must provide clear instructions and update structured state.")
                    .stringParamAdd("target", "Target expert: [" + expertList + "]")
                    .stringParamAdd("instruction", "Specific instruction for the next agent")
                    .stringParamAdd("state", "Updated structured state (JSON) to pass key data, IDs, etc.");
        }

        toolDesc.doHandle(args -> "System: Handover recorded.");
        trace.addProtocolTool(toolDesc);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        String instruction = (String) trace.getProtocolContext().get(KEY_LAST_INSTRUCTION);
        A2AState globalState = (A2AState) trace.getProtocolContext().get(KEY_GLOBAL_STATE);

        if (Utils.isNotEmpty(instruction) || (globalState != null && !globalState.isEmpty())) {
            List<ChatMessage> messages = new ArrayList<>();

            // 1. 优先保留 System Message
            originalPrompt.getMessages().stream()
                    .filter(msg -> msg.getRole() == ChatRole.SYSTEM)
                    .forEach(messages::add);

            // 2. 注入 A2A 上下文
            String compositeUserContent = buildA2AContext(originalPrompt, instruction, globalState, locale);
            messages.add(ChatMessage.ofUser(compositeUserContent));

            // 3. 指令一次性消费
            trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);

            return Prompt.of(messages);
        }

        return originalPrompt;
    }

    private String buildA2AContext(Prompt originalPrompt, String instruction, A2AState state, Locale locale) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();

        sb.append(isZh ? "## 任务交接上下文\n" : "## Task Handover Context\n");

        if (Utils.isNotEmpty(instruction)) {
            sb.append(isZh ? "### 当前指令:\n" : "### Current Instruction:\n").append(instruction).append("\n\n");
        }

        if (state != null && !state.isEmpty()) {
            sb.append(isZh ? "### 结构化数据状态 (JSON):\n" : "### Structured State (JSON):\n")
                    .append("```json\n").append(state.toString()).append("\n```\n\n");
        }

        sb.append("---\n");
        sb.append(isZh ? "### 原始用户需求:\n" : "### Original Requirement:\n")
                .append(extractOriginalUserContent(originalPrompt));

        return sb.toString();
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String lastAgentName = trace.getLastAgentName();
        if (Utils.isEmpty(lastAgentName)) return null;

        AgentTrace latestTrace = context.getAs("__" + lastAgentName);
        if (latestTrace instanceof ReActTrace) {
            ReActTrace rt = (ReActTrace) latestTrace;

            // 提取 Instruction
            String instruction = extractValueFromToolCalls(rt, "instruction");
            if (Utils.isNotEmpty(instruction)) {
                trace.getProtocolContext().put(KEY_LAST_INSTRUCTION, instruction);
            }

            // 提取并更新 State
            String stateJson = extractValueFromToolCalls(rt, "state");
            if (Utils.isNotEmpty(stateJson)) {
                A2AState state = (A2AState) trace.getProtocolContext()
                        .computeIfAbsent(KEY_GLOBAL_STATE, k -> new A2AState());
                state.merge(stateJson);
            }

            // 目标路由解析
            String rawTarget = extractValueFromToolCalls(rt, "target");
            if (Utils.isNotEmpty(rawTarget)) {
                String target = cleanTargetName(rawTarget);
                target = findBestMatchAgent(target, trace);

                if (enableLoopDetection && isTransferLoop(trace, lastAgentName, target)) {
                    LOG.warn("A2A loop detected: {} -> {}", lastAgentName, target);
                    trace.addStep(Agent.ID_SUPERVISOR, "Loop detected, terminating.", 0);
                    return Agent.ID_END;
                }

                recordTransfer(trace, lastAgentName, target);
                return target;
            }
        }
        return null;
    }

    private String extractOriginalUserContent(Prompt originalPrompt) {
        String content = originalPrompt.getMessages().stream()
                .filter(msg -> msg.getRole() == ChatRole.USER)
                .map(ChatMessage::getContent)
                .collect(Collectors.joining("\n\n"));
        return Utils.isEmpty(content) ? "No content" : content;
    }

    private String cleanTargetName(String rawTarget) {
        if (Utils.isEmpty(rawTarget)) return rawTarget;
        int bracketIndex = rawTarget.indexOf('(');
        return (bracketIndex > 0 ? rawTarget.substring(0, bracketIndex) : rawTarget).trim();
    }

    private String findBestMatchAgent(String name, TeamTrace trace) {
        if (config.getAgentMap().containsKey(name)) return name;
        for (String agentName : config.getAgentMap().keySet()) {
            if (agentName.equalsIgnoreCase(name)) return agentName;
        }
        return name;
    }

    private void recordTransfer(TeamTrace trace, String from, String to) {
        List<String> history = (List<String>) trace.getProtocolContext()
                .computeIfAbsent(KEY_TRANSFER_HISTORY, k -> new ArrayList<>());
        history.add(from);
        history.add(to);
        // 限制历史长度，只保留最近10次跳转
        if (history.size() > 20) {
            history.subList(0, 2).clear();
        }
    }

    private boolean isTransferLoop(TeamTrace trace, String from, String to) {
        List<String> history = (List<String>) trace.getProtocolContext().get(KEY_TRANSFER_HISTORY);
        if (history == null || history.size() < 2) return false;
        String lastFrom = history.get(history.size() - 2);
        String lastTo = history.get(history.size() - 1);
        return lastFrom.equals(to) && lastTo.equals(from);
    }

    private String extractValueFromToolCalls(ReActTrace reactTrace, String key) {
        List<ChatMessage> messages = reactTrace.getMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (am.getToolCalls() != null) {
                    for (ToolCall tc : am.getToolCalls()) {
                        if (TOOL_TRANSFER.equals(tc.name())) {
                            return extractFromJson(tc.arguments(), key);
                        }
                    }
                }
            }
        }
        return null;
    }

    private String extractFromJson(Object arguments, String key) {
        if (arguments instanceof Map) {
            Object val = ((Map<?, ?>) arguments).get(key);
            return val == null ? null : val.toString();
        } else if (arguments instanceof String) {
            try {
                return ONode.ofJson((String) arguments).get(key).getString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);
        trace.getProtocolContext().remove(KEY_GLOBAL_STATE);
        trace.getProtocolContext().remove(KEY_TRANSFER_HISTORY);
    }
}