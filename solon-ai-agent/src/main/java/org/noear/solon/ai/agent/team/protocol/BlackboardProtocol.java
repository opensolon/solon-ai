/*
 * Copyright 2017-2025 noear.org and authors
 * ... (License 保持不变)
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
 * 优化版黑板协作协议 (Blackboard Protocol)
 *
 * 优化点：
 * 1. 结构化任务管理：对 todo 列表进行去重和去空处理。
 * 2. 多模态看板友好：自动截断看板中的长文本，防止 Token 溢出。
 * 3. 协作溯源：记录最后更新看板的成员 ID。
 */
@Preview("3.8.1")
public class BlackboardProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol.class);
    private static final String KEY_BOARD_DATA = "blackboard_state_obj";
    private static final String TOOL_SYNC = "__sync_to_blackboard__";

    public static class BoardState {
        private final ONode data = new ONode().asObject();
        private final Set<String> todos = new LinkedHashSet<>(); // 使用 Set 自动去重
        private String lastUpdater;

        public void merge(String agentName, String json) {
            if (Utils.isEmpty(json)) return;
            try {
                ONode node = ONode.ofJson(json);
                if (node.isObject()) {
                    this.lastUpdater = agentName;
                    node.getObjectUnsafe().forEach((k, v) -> {
                        if ("todo".equalsIgnoreCase(k)) {
                            if (v.isArray()) {
                                v.getArrayUnsafe().forEach(i -> {
                                    if (Utils.isNotEmpty(i.getString())) todos.add(i.getString());
                                });
                            } else if (v.isValue()) {
                                todos.add(v.getString());
                            }
                        } else {
                            data.set(k, v);
                        }
                    });
                }
            } catch (Exception e) {
                LOG.warn("Blackboard merge failed from {}: {}", agentName, json);
            }
        }

        @Override
        public String toString() {
            ONode root = ONode.ofJson(data.toJson());
            // 保护性截断：如果黑板中包含超长数据，进行简略处理
            root.getObjectUnsafe().forEach((k, v) -> {
                if (v.isString() && v.getString().length() > 500) {
                    root.set(k, v.getString().substring(0, 500) + "...[truncated]");
                }
            });

            if (!todos.isEmpty()) {
                ONode todoNode = root.getOrNew("todo").asArray();
                todos.forEach(todoNode::add);
            }

            root.getOrNew("_meta").set("last_updater", lastUpdater);
            return root.toJson();
        }
    }

    public BlackboardProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "BLACKBOARD"; }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_SYNC);
        if (isZh) {
            toolDesc.title("更新黑板")
                    .description("将你的核心结论、发现、以及建议的后续任务(todo)同步到全局黑板。")
                    .stringParamAdd("state", "JSON 格式。示例：{\"result\":\"已完成调研\", \"todo\":[\"指派A进行测试\"]}");
        } else {
            toolDesc.title("Update Blackboard")
                    .description("Sync your findings and suggested 'todo' items to the global blackboard.")
                    .stringParamAdd("state", "JSON format. Example: {\"result\":\"Research done\", \"todo\":[\"Assign A to test\"]}");
        }

        toolDesc.doHandle(args -> "System: Blackboard state updated successfully.");
        trace.addProtocolTool(toolDesc);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 全局黑板 (Blackboard Dashboard)\n" : "\n### Global Blackboard\n");
        if (state != null && (!state.data.isEmpty() || !state.todos.isEmpty())) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
        } else {
            sb.append(isZh ? "> 状态：黑板目前为空，请下达初始指令。\n" : "> Status: Blackboard is empty. Issue initial instructions.\n");
        }

        // 依然注入 Hierarchical 的元数据（负载统计、错误看板等）
        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 从当前 Agent 的 ReAct 运行轨迹中提取同步指令
        ReActTrace rt = trace.getContext().getAs("__" + agent.name());
        if (rt != null) {
            String rawState = extractValueFromTool(rt, TOOL_SYNC, "state");
            if (Utils.isNotEmpty(rawState)) {
                BoardState state = (BoardState) trace.getProtocolContext()
                        .computeIfAbsent(KEY_BOARD_DATA, k -> new BoardState());
                state.merge(agent.name(), rawState);
            }
        }
        super.onAgentEnd(trace, agent);
    }

    private String extractValueFromTool(ReActTrace rt, String toolName, String key) {
        List<ChatMessage> messages = rt.getMessages();
        if (messages == null) return null;
        // 逆序查找，确保拿到的是最新的同步数据
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) messages.get(i);
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
            try { return ONode.ofJson((String) args).get(key).getString(); } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 黑板管理原则：\n");
            sb.append("1. **消耗 todo**：优先指派专家完成黑板中列出的 `todo` 任务。\n");
            sb.append("2. **技能校准**：对比 `todo` 描述与专家的 **Skills**，确保人岗匹配。\n");
            sb.append("3. **冲突解决**：若黑板信息出现冲突，请指派核心专家进行修正。");
        } else {
            sb.append("\n### Blackboard Management:\n");
            sb.append("1. **Consume Todos**: Prioritize tasks listed in the `todo` section of the blackboard.\n");
            sb.append("2. **Skill Alignment**: Match `todo` descriptions with agents' **Skills**.\n");
            sb.append("3. **Conflict Resolution**: If blackboard data is contradictory, assign a senior agent to resolve.");
        }
    }
}