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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A2A (Agent to Agent) 协作协议
 * 实现智能体之间的任务移交与上下文状态衔接
 *
 * @author noear
 * @since 3.8.1
 */
public class A2AProtocol extends TeamProtocolBase {
    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_MEMO = "last_memo";

    public A2AProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        // [阶段：构建期] 默认从第一个智能体开始执行
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 所有专家节点执行完后，统一上报给主管（Supervisor）
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        // 路由器配置
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = trace.getConfig().getPromptProvider().getLocale();

        // 排除当前 Agent 自身，生成备选专家列表
        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(a -> a.name() + (Utils.isNotEmpty(a.descriptionFor(trace.getContext())) ? "(" + a.descriptionFor(trace.getContext()) + ")" : ""))
                .collect(Collectors.joining(", "));

        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_TRANSFER);

        // 注入系统级移交工具
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            toolDesc.title("移交任务")
                    .description("当你无法独立完成当前任务时，调用此工具将控制权移交给其他专家。")
                    .stringParamAdd("target", "目标专家名称，可选范围: [" + expertList + "]")
                    .stringParamAdd("memo", "接棒说明：说明当前已完成工作和后续重点")
                    .doHandle(args -> "系统：移交指令已记录，正在切换执行者...");
        } else {
            toolDesc.title("Transfer Task")
                    .description("Transfer control to another expert when you cannot complete the task independently.")
                    .stringParamAdd("target", "Target expert name, candidates: [" + expertList + "]")
                    .stringParamAdd("memo", "Handover memo: explain progress and focus for the next agent")
                    .doHandle(args -> "System: Transfer command recorded. Switching agent...");
        }

        trace.addProtocolTool(toolDesc);
    }

    @Override
    public void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) {
        sb.append("\n\n[Collaboration Rules]");
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n- 如需寻求协助，请使用工具 `").append(TOOL_TRANSFER).append("`。");
            sb.append("\n- 只有在任务完全结束时，才输出回复包含 \"").append(config.getFinishMarker()).append("\"。");
        } else {
            sb.append("\n- Use tool `").append(TOOL_TRANSFER).append("` to delegate tasks.");
            sb.append("\n- Only output \"").append(config.getFinishMarker()).append("\" when the entire task is finalized.");
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // [阶段：执行前] 注入前序 Agent 留下的备注（Memo）
        String memo = (String) trace.getProtocolContext().get(KEY_LAST_MEMO);

        if (Utils.isNotEmpty(memo)) {
            boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());
            String hint = isChinese ? "【接棒提示】： " : "[Handover Hint]: ";
            originalPrompt.getMessages().add(0, ChatMessage.ofSystem(hint + memo));

            // 使用后即从上下文清理，确保一次性消费
            trace.getProtocolContext().remove(KEY_LAST_MEMO);
        }

        return originalPrompt;
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String lastAgentName = context.getAs(Agent.KEY_LAST_AGENT_NAME);
        if (Utils.isEmpty(lastAgentName)) return null;

        // [调整点] 统一从 FlowContext 获取 Agent 自身的轨迹
        AgentTrace latestTrace = context.getAs("__" + lastAgentName);

        if (latestTrace instanceof ReActTrace) {
            ReActTrace rt = (ReActTrace) latestTrace;

            // 提取 Memo 并存入 ProtocolContext (用于下个节点的 prepareAgentPrompt)
            String memo = extractValueFromToolCalls(rt, "memo");
            if (Utils.isNotEmpty(memo)) {
                trace.getProtocolContext().put(KEY_LAST_MEMO, memo);
            }

            // 优先返回显式 target
            String target = extractValueFromToolCalls(rt, "target");
            if (Utils.isNotEmpty(target)) {
                return target;
            }
        }

        // 2. 兜底解析：如果 LLM 在 Decision 中提到了转交工具但没调用，或直接提到了名字
        if (decision.contains(TOOL_TRANSFER)) {
            for (String agentName : config.getAgentMap().keySet()) {
                if (decision.contains(agentName)) return agentName;
            }
        }

        return null; // 交给 Supervisor 继续匹配
    }

    /**
     * 从轨迹中最后一次工具调用提取特定参数
     */
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
                            return extractValue(tc.arguments(), key);
                        }
                    }
                }
            }
        }
        return null;
    }

    private String extractValue(Object arguments, String key) {
        if (arguments instanceof java.util.Map) {
            Object val = ((java.util.Map<?, ?>) arguments).get(key);
            return val == null ? null : val.toString();
        } else if (arguments instanceof String) {
            String json = (String) arguments;
            if (json.trim().startsWith("{")) {
                try {
                    return ONode.ofJson(json).get(key).getString();
                } catch (Exception e) { /* ignore */ }
            }
        }
        return null;
    }
}