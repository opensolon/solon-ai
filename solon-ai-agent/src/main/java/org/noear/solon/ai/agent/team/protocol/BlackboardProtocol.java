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
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 黑板协作协议 (Blackboard Protocol)
 *
 * <p>核心特征：全局共享看板。Agent 通过工具主动同步结论和待办事项，
 * Supervisor 依据黑板上的数据补全情况和 `todo` 列表决定下一步路由。</p>
 */
@Preview("3.8.2")
public class BlackboardProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol.class);
    private static final String KEY_BOARD_DATA = "blackboard_state_obj";
    private static final String TOOL_SYNC = "__sync_to_blackboard__";

    /**
     * 黑板状态机：管理共享数据与待办队列 (TODOs)
     */
    public static class BoardState {
        private final ONode data = new ONode().asObject();
        public final Set<String> todos = new LinkedHashSet<>();
        private String lastUpdater;

        /**
         * 结构化合并数据
         */
        public void merge(String agentName, Object rawInput) {
            if (rawInput == null) return;
            this.lastUpdater = agentName;
            try {
                ONode node = ONode.ofBean(rawInput);
                if (node.isObject()) {
                    node.getObjectUnsafe().forEach((k, v) -> {
                        if ("todo".equalsIgnoreCase(k)) {
                            addTodoNode(v);
                        } else {
                            data.set(k, v);
                        }
                    });
                } else {
                    data.set("result_" + agentName, node.getString());
                }
            } catch (Exception e) {
                LOG.warn("Blackboard Protocol: Merge failed for [{}], payload: {}", agentName, rawInput);
            }
        }

        public void addDirect(String agentName, String result, String todo) {
            this.lastUpdater = agentName;
            if (Utils.isNotEmpty(result)) data.set("output_" + agentName, result);
            if (Utils.isNotEmpty(todo)) {
                Arrays.asList(todo.split("[,;，；\n]")).forEach(t -> {
                    if (Utils.isNotEmpty(t.trim())) todos.add(t.trim());
                });
            }
        }

        private void addTodoNode(ONode v) {
            if (v.isArray()) {
                v.getArrayUnsafe().forEach(i -> {
                    if (Utils.isNotEmpty(i.getString())) todos.add(i.getString());
                });
            } else if (v.isValue()) {
                todos.add(v.getString());
            }
        }

        @Override
        public String toString() {
            ONode root = ONode.ofJson(data.toJson());
            if (!todos.isEmpty()) {
                ONode todoNode = root.getOrNew("todo").asArray();
                todos.forEach(todoNode::add);
            }
            root.getOrNew("_meta").set("last_updater", lastUpdater);
            return root.toJson();
        }
    }

    public BlackboardProtocol(TeamAgentConfig config) { super(config); }

    @Override
    public String name() { return "BLACKBOARD"; }

    /**
     * 为 Agent 注入黑板同步工具，实现主动数据回传
     */
    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_SYNC);
        if (isZh) {
            toolDesc.title("同步黑板").description("同步你的结论和后续待办。建议在完成任务前调用。")
                    .stringParamAdd("result", "本阶段的确切结论")
                    .stringParamAdd("todo", "建议后续待办事项")
                    .stringParamAdd("state", "JSON 格式的详细业务数据");
        } else {
            toolDesc.title("Sync Blackboard").description("Sync findings and future todos.")
                    .stringParamAdd("result", "Key findings")
                    .stringParamAdd("todo", "Suggested next steps")
                    .stringParamAdd("state", "Detailed JSON state");
        }
        toolDesc.doHandle(args -> "System: Blackboard updated.");
        receiver.accept(toolDesc);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        if (state == null) return originalPrompt;

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        List<ChatMessage> messages = new ArrayList<>(originalPrompt.getMessages());
        String info = isZh ? "当前协作黑板内容：" : "Current blackboard content: ";

        // 注入到消息列表顶部，作为 Agent 的上下文感知
        messages.add(1, ChatMessage.ofSystem(info + "\n" + state.toString()));
        return Prompt.of(messages);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        ReActTrace rt = trace.getContext().getAs("__" + agent.name());
        if (rt != null) {
            BoardState state = (BoardState) trace.getProtocolContext().computeIfAbsent(KEY_BOARD_DATA, k -> new BoardState());
            String res = extractArg(rt, TOOL_SYNC, "result");
            String todo = extractArg(rt, TOOL_SYNC, "todo");
            Object rawState = extractArgObj(rt, TOOL_SYNC, "state");

            if (Utils.isNotEmpty(res) || Utils.isNotEmpty(todo)) state.addDirect(agent.name(), res, todo);
            if (rawState != null) state.merge(agent.name(), rawState);
        }
        super.onAgentEnd(trace, agent);
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 显式终结符判定，防止 Supervisor 在任务收尾阶段胡乱路由
        String lastContent = trace.getLastAgentContent();
        if (lastContent != null && (lastContent.contains("FINISH]") || lastContent.contains("Final Answer:"))) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("Blackboard Protocol: Terminal signal detected, ending task.");
            }
            return null;
        }
        return super.resolveSupervisorRoute(context, trace, decision);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 全局黑板 (Blackboard Dashboard)\n" : "\n### Global Blackboard\n");
        sb.append(state != null ? "```json\n" + state + "\n```\n" : "- Empty\n");
        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 黑板管理原则：\n");
            sb.append("1. **依据待办**：优先处理黑板中 `todo` 列表的任务。\n");
            sb.append("2. **结论导向**：若所有待办已完成且信息补全，整理黑板结论并结束。");
        } else {
            sb.append("\n### Blackboard Management:\n");
            sb.append("1. **Todo Driven**: Prioritize agents based on `todo` list.\n");
            sb.append("2. **Summary**: Finalize findings when todos are cleared.");
        }
    }

    /**
     * 终结审计：如果黑板中尚存待办事项且未达轮次上限，拦截 [FINISH] 路由
     */
    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            if (config.getGraphAdjuster() != null) return true;

            BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
            if (state != null && !state.todos.isEmpty()) {
                if (trace.getIterationsCount() < 5) {
                    LOG.warn("Blackboard Protocol: Blocking finish! Pending todos exist: {}", state.todos);
                    return false;
                }
            }
        }
        return super.shouldSupervisorRoute(context, trace, decision);
    }

    private String extractArg(ReActTrace rt, String tool, String key) {
        Object val = extractArgObj(rt, tool, key);
        return val == null ? null : String.valueOf(val);
    }

    /**
     * 参数提取：从 ReActTrace 中溯源特定的工具调用参数
     */
    private Object extractArgObj(ReActTrace rt, String toolName, String key) {
        List<ChatMessage> messages = rt.getMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage ) {
                AssistantMessage am = (AssistantMessage) messages.get(i);
                if (am.getToolCalls() != null) {
                    for (ToolCall tc : am.getToolCalls()) {
                        if (toolName.equals(tc.name())) return tc.arguments().get(key);
                    }
                }
            }
        }
        return null;
    }
}