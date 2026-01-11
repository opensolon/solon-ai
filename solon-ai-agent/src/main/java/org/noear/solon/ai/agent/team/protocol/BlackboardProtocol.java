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
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 黑板协作协议 (Blackboard Protocol)
 *
 * 特点：
 * 1. 独立看板管理：通过同步工具 (__sync_to_blackboard__) 维护全局共识。
 * 2. 任务涌现：自动提取并维护 todo 列表。
 * 3. 状态闭环：Supervisor 基于看板状态决定是否继续派发任务。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class BlackboardProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol.class);

    private static final String KEY_BOARD_DATA = "blackboard_state_obj";
    private static final String TOOL_SYNC = "__sync_to_blackboard__";

    public static class BoardState {
        private final ONode data = new ONode().asObject();
        private final List<String> todos = new ArrayList<>();

        public void merge(String json) {
            if (Utils.isEmpty(json)) return;
            try {
                ONode node = ONode.ofJson(json);
                if (node.isObject()) {
                    node.getObjectUnsafe().forEach((k, v) -> {
                        if ("todo".equalsIgnoreCase(k) && v.isArray()) {
                            v.getArrayUnsafe().forEach(i -> {
                                String task = i.getString();
                                if (Utils.isNotEmpty(task) && !todos.contains(task)) {
                                    todos.add(task);
                                }
                            });
                        } else {
                            data.set(k, v);
                        }
                    });
                }
            } catch (Exception e) {
                LOG.warn("Blackboard state merge failed: {}", json);
            }
        }

        public boolean isEmpty() {
            return data.isEmpty() && todos.isEmpty();
        }

        @Override
        public String toString() {
            // 使用 JSON 中转实现深拷贝，避免直接操作原对象
            ONode root = ONode.ofJson(data.toJson());
            if (!todos.isEmpty()) {
                ONode todoNode = root.getOrNew("todo").asArray();
                todos.forEach(todoNode::add);
            }
            return root.toJson();
        }
    }

    public BlackboardProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "BLACKBOARD";
    }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = config.getLocale();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_SYNC);
        if (isZh) {
            toolDesc.title("同步到黑板")
                    .description("将本阶段的核心结论或下一步计划同步到全局黑板。")
                    .stringParamAdd("state", "JSON格式数据。建议：在 todo 中指派任务时，请务必核对目标成员的“行为约束(Constraints)”。");
        } else {
            toolDesc.title("Sync to Blackboard")
                    .description("Synchronize findings or next steps to the shared blackboard.")
                    .stringParamAdd("state", "JSON data. Suggestion: When assigning 'todo' items, ensure they do not violate the target member's 'Constraints'.");
        }

        toolDesc.doHandle(args -> "System: Blackboard updated.");
        trace.addProtocolTool(toolDesc);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 黑板看板 (Blackboard Consensus)\n" : "\n### Blackboard Consensus\n");
        if (state != null && !state.isEmpty()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
        } else {
            sb.append(isZh ? "> 暂无共识数据\n" : "> No consensus data yet\n");
        }

        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 在 Agent 执行结束时，尝试从上下文存储的 ReActTrace 中提取黑板更新
        ReActTrace rt = trace.getContext().getAs("__" + agent.name());
        if (rt != null) {
            String rawState = extractValueFromTool(rt, TOOL_SYNC, "state");
            if (Utils.isNotEmpty(rawState)) {
                BoardState state = (BoardState) trace.getProtocolContext()
                        .computeIfAbsent(KEY_BOARD_DATA, k -> new BoardState());
                state.merge(rawState);
                LOG.debug("Blackboard state updated by: {}", agent.name());
            }
        }
        super.onAgentEnd(trace, agent);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 黑板协作守则：\n");
            sb.append("1. **精准派发**：对比黑板上的 `todo` 与成员档案的 **擅长技能 (Skills)**，派发给最专业的成员。\n");
            sb.append("2. **合规审计**：严禁指派违背成员 **行为约束 (Constraints)** 的任务。\n");
            sb.append("3. **增量更新**：专家应通过同步工具不断完善看板内容。");
        } else {
            sb.append("\n### Blackboard Rules:\n");
            sb.append("1. **Precision Dispatch**: Match `todo` items with members' **Skills** from their profiles.\n");
            sb.append("2. **Compliance Audit**: Never assign tasks that violate a member's **Constraints**.\n");
            sb.append("3. **Incremental Sync**: Experts must update the board via sync tools.");
        }
    }

    private String extractValueFromTool(ReActTrace rt, String toolName, String key) {
        List<ChatMessage> messages = rt.getMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                List<ToolCall> toolCalls = am.getToolCalls();
                if (toolCalls != null) {
                    for (ToolCall tc : toolCalls) {
                        if (toolName.equals(tc.name())) {
                            return extractJsonValue(tc.arguments(), key);
                        }
                    }
                }
            }
        }
        return null;
    }

    private String extractJsonValue(Object args, String key) {
        if (args instanceof Map) {
            return String.valueOf(((Map<?, ?>) args).get(key));
        }
        if (args instanceof String) {
            try {
                return ONode.ofJson((String) args).get(key).getString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_BOARD_DATA);
        super.onTeamFinished(context, trace);
    }
}