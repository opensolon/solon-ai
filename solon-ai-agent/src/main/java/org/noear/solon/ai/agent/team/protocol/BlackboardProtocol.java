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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 强化版黑板协作协议 (Blackboard Protocol)
 * 1. 参数平铺：解决嵌套 JSON 导致的 Unclosed string 报错。
 * 2. 指令补全：补全 Supervisor 和 Agent 的注入逻辑，确保协作不脱节。
 * 3. 强力终结：解决 Supervisor 在任务完成后盲目路由导致的死循环。
 */
@Preview("3.8.2")
public class BlackboardProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol.class);
    private static final String KEY_BOARD_DATA = "blackboard_state_obj";
    private static final String TOOL_SYNC = "__sync_to_blackboard__";

    public static class BoardState {
        private final ONode data = new ONode().asObject();
        public final Set<String> todos = new LinkedHashSet<>();
        private String lastUpdater;

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
                LOG.warn("Blackboard merge failed: {}", rawInput);
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

    public BlackboardProtocol(TeamConfig config) { super(config); }

    @Override
    public String name() { return "BLACKBOARD"; }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_SYNC);
        if (isZh) {
            toolDesc.title("同步黑板").description("同步你的结论和后续待办。推荐使用 result 和 todo 参数。")
                    .stringParamAdd("result", "本阶段的确切产出（如表名、SQL）")
                    .stringParamAdd("todo", "建议后续待办，多项用分号分隔")
                    .stringParamAdd("state", "JSON 格式数据（可选）");
        } else {
            toolDesc.title("Sync Blackboard").description("Sync findings and todos.")
                    .stringParamAdd("result", "Findings or deliverables")
                    .stringParamAdd("todo", "Next steps, comma separated")
                    .stringParamAdd("state", "JSON (Optional)");
        }
        toolDesc.doHandle(args -> "System: Blackboard updated.");
        trace.addProtocolTool(toolDesc);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        if (state == null) return originalPrompt;

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        List<ChatMessage> messages = new ArrayList<>(originalPrompt.getMessages());
        String info = isZh ? "当前协作黑板内容：" : "Current blackboard content: ";
        // 注入到 System 消息之后，防止被忽略
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
        // 强力终结：解决 Supervisor 死循环
        String lastContent = trace.getLastAgentContent();
        if (lastContent != null && (lastContent.contains("FINISH]") || lastContent.contains("Final Answer:"))) {
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
            sb.append("1. **依据待办**：优先指派专家完成黑板中列出的 `todo` 任务。\n");
            sb.append("2. **按需补充**：若黑板中缺失关键信息（如表名、API定义），请指派对应专家补齐。\n");
            sb.append("3. **结果确认**：若任务已完成，请整理黑板结论并以 [FINISH] 结束。");
        } else {
            sb.append("\n### Blackboard Management:\n");
            sb.append("1. **By Todo**: Prioritize tasks listed in the `todo` section.\n");
            sb.append("2. **Completeness**: Assign agents to fill missing info (e.g., table names) on the board.\n");
            sb.append("3. **Finalize**: If all steps are done, summarize findings and end with [FINISH].");
        }
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            // 如果有自定义图，直接放行
            if (config.getGraphAdjuster() != null) {
                return true;
            }

            BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
            // 标准模式：如果黑板里还有待办事项，且轮次还没到熔断阈值，则拦截
            if (state != null && !state.todos.isEmpty()) {
                if (trace.getIterationsCount() < 5) {
                    LOG.warn("BlackboardProtocol: Still has pending todos {}, blocking finish.", state.todos);
                    return false;
                }
            }
        }
        // 兜底调用父类（Hierarchical）的专家参与度检查
        return super.shouldSupervisorRoute(context, trace, decision);
    }

    private String extractArg(ReActTrace rt, String tool, String key) {
        Object val = extractArgObj(rt, tool, key);
        return val == null ? null : String.valueOf(val);
    }

    private Object extractArgObj(ReActTrace rt, String toolName, String key) {
        List<ChatMessage> messages = rt.getMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage ) {
                AssistantMessage  am = (AssistantMessage) messages.get(i);
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