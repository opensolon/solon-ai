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
 * 黑板协作协议 (Blackboard Protocol) - 增强独立版
 * * 特点：
 * 1. 独立状态管理 (BoardState)，不与其他协议耦合。
 * 2. 结构化看板驱动，自动提取并增量维护任务清单 (Todo List)。
 * 3. 强化数据持久性，确保关键结论跨轮次存在。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class BlackboardProtocol_H extends HierarchicalProtocol_H {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol_H.class);

    private static final String KEY_BOARD_DATA = "blackboard_state_obj";
    private static final String TOOL_SYNC = "__sync_to_blackboard__";

    /**
     * 黑板协议专用的内部状态对象
     */
    public static class BoardState {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<String> todos = new ArrayList<>();

        public void merge(String json) {
            if (Utils.isEmpty(json)) return;
            try {
                // 使用 SNACK4 4.0 推荐的 load 方式
                ONode node = ONode.ofJson(json);
                if (node.isObject()) {
                    node.getObjectUnsafe().forEach((k, v) -> {
                        if ("todo".equalsIgnoreCase(k) && v.isArray()) {
                            // 增量更新任务清单，避免重复
                            v.getArrayUnsafe().forEach(i -> {
                                String task = i.getString();
                                if (Utils.isNotEmpty(task) && !todos.contains(task)) {
                                    todos.add(task);
                                }
                            });
                        } else {
                            // 深度转换为 POJO/Map 存储
                            data.put(k, v.toBean());
                        }
                    });
                }
            } catch (Exception e) {
                LOG.warn("Blackboard state merge failed: {}", json, e);
            }
        }

        public boolean isEmpty() {
            return data.isEmpty() && todos.isEmpty();
        }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            data.forEach(root::set);
            if (!todos.isEmpty()) {
                ONode todoNode = root.getOrNew("todo");
                todos.forEach(todoNode::add);
            }
            return root.toJson();
        }
    }

    public BlackboardProtocol_H(TeamConfig config) {
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
                    .description("将本阶段的核心结论或下一步计划同步到全局黑板看板。")
                    .stringParamAdd("state", "JSON格式数据。示例: {\"project_id\":\"123\", \"todo\":[\"执行代码生成的专家检查\"]}");
        } else {
            toolDesc.title("Sync to Blackboard")
                    .description("Synchronize key findings or next steps to the shared blackboard.")
                    .stringParamAdd("state", "JSON data. E.g., {\"status\":\"validated\", \"todo\":[\"run security scan\"]}");
        }

        toolDesc.doHandle(args -> "System: Blackboard state updated.");
        trace.addProtocolTool(toolDesc);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n\n### 💡 黑板看板 (当前共识)\n" : "\n\n### 💡 Blackboard (Current Consensus)\n");
        if (state != null && !state.isEmpty()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
        } else {
            sb.append(isZh ? "> 尚无看板数据，等待专家上报...\n" : "> No board data, waiting for expert reports...\n");
        }

        // 继承父类的步骤摘要
        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String lastAgent = trace.getLastAgentName();
        if (Utils.isNotEmpty(lastAgent)) {
            AgentTrace latestTrace = context.getAs("__" + lastAgent);
            if (latestTrace instanceof ReActTrace) {
                String rawState = extractValueFromTool((ReActTrace) latestTrace, TOOL_SYNC, "state");
                if (Utils.isNotEmpty(rawState)) {
                    BoardState state = (BoardState) trace.getProtocolContext()
                            .computeIfAbsent(KEY_BOARD_DATA, k -> new BoardState());
                    state.merge(rawState);
                }
            }
        }
        return super.resolveSupervisorRoute(context, trace, decision);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 黑板协作守则\n");
            sb.append("- **依据看板决策**：优先处理 JSON 中 todo 列表里的事项。\n");
            sb.append("- **数据闭环**：如果看板已提供所需答案，请直接总结并结束。");
        } else {
            sb.append("\n## Blackboard Rules\n");
            sb.append("- **State-Based Decision**: Prioritize items in the JSON 'todo' list.\n");
            sb.append("- **Early Exit**: If the board contains sufficient answers, conclude the task.");
        }
    }

    private String extractValueFromTool(ReActTrace rt, String toolName, String key) {
        List<ChatMessage> messages = rt.getMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (am.getToolCalls() != null) {
                    for (ToolCall tc : am.getToolCalls()) {
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
        if (args instanceof Map) return String.valueOf(((Map<?, ?>) args).get(key));
        if (args instanceof String) {
            try {
                return ONode.ofJson((String) args).get(key).getString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_BOARD_DATA);
        super.onTeamFinished(context, trace);
    }
}