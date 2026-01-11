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
 * A2A (Agent to Agent) 协作协议实现
 * 基于 Instruction (指令接力) 和 State (结构化状态机)
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class A2AProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(A2AProtocol.class);

    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_INSTRUCTION = "last_instruction";
    private static final String KEY_GLOBAL_STATE = "global_state";

    public A2AProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "A2A";
    }

    // --- 阶段一：构建期 ---
    @Override
    public void buildGraph(GraphSpec spec) {
        // A2A 通常由第一个 Agent 发起
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 所有 Agent 执行完都去 Supervisor 报到（由 Supervisor 解析 A2A 工具调用）
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns); // 动态路由
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    // --- 阶段二：Agent 生命周期 ---

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = config.getLocale();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        // 改进：不仅仅是名字，还要把专家的 Profile 核心信息带出来
        String expertsDescription = config.getAgentMap().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(agent.name())) // 排除自己
                .map(entry -> {
                    String name = entry.getKey();
                    Agent expert = entry.getValue();
                    // 获取格式化后的 Profile 信息（如：擅长技能: java; 行为约束: ...）
                    String profileSummary = (expert.profile() != null)
                            ? expert.profile().toFormatString(locale)
                            : "";
                    return name + (profileSummary.isEmpty() ? "" : " (" + profileSummary + ")");
                })
                .collect(Collectors.joining(" | "));

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_TRANSFER);
        if (isZh) {
            tool.title("任务移交").description("将当前任务移交给团队中更合适的专家进行接力处理")
                    .stringParamAdd("target", "目标专家名。可选范围: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "给接棒专家的具体指令，说明需要他做什么")
                    .stringParamAdd("state", "需要传递的结构化数据状态(JSON格式)");
        } else {
            tool.title("Transfer").description("Transfer the current task to a more suitable expert in the team")
                    .stringParamAdd("target", "Target expert name. Options: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "Specific instruction for the next expert")
                    .stringParamAdd("state", "Data state to transfer (JSON format)");
        }
        trace.addProtocolTool(tool);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        String instruction = (String) trace.getProtocolContext().get(KEY_LAST_INSTRUCTION);
        String state = (String) trace.getProtocolContext().get(KEY_GLOBAL_STATE);

        // 如果没有交接信息，直接返回原始 Prompt
        if (Utils.isEmpty(instruction) && Utils.isEmpty(state)) {
            return originalPrompt;
        }

        List<ChatMessage> messages = new ArrayList<>();
        // 保持 System Message 优先级
        originalPrompt.getMessages().stream().filter(m -> m.getRole() == ChatRole.SYSTEM).forEach(messages::add);

        // 构建 A2A 专属上下文
        StringBuilder sb = new StringBuilder();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        sb.append(isZh ? "### 任务接力上下文\n" : "### Task Handover Context\n");
        if (Utils.isNotEmpty(instruction)) {
            sb.append("- ").append(isZh ? "接棒指令: " : "Instruction: ").append(instruction).append("\n");
        }
        if (Utils.isNotEmpty(state)) {
            sb.append("- ").append(isZh ? "当前数据状态: " : "Current State: ").append(state).append("\n");
        }
        sb.append("\n---\n");

        // 合并原始 User 消息
        String userContent = originalPrompt.getMessages().stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(ChatMessage::getContent).collect(Collectors.joining("\n"));
        sb.append(userContent);

        messages.add(ChatMessage.ofUser(sb.toString()));

        // 指令属于瞬时数据，消费后即刻移除
        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);

        return Prompt.of(messages);
    }

    // --- 阶段三：主管决策治理 ---

    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String rawContent) {
        if (Utils.isEmpty(rawContent)) return rawContent;

        // 尝试从 rawContent 中提取工具调用的结果（如果 LLM 直接输出了 JSON 块或特定格式）
        // 在 A2A 中，如果通过工具触发，AgentTrace 会记录 ToolCall。
        // 但如果 Agent 通过文本模拟指令，这里可以进行“文本特征提取”。

        // 我们保持对 ReActTrace 的兼容解析，但这里可以过滤掉文本中的交互指令
        return rawContent.replaceAll("\\{" + TOOL_TRANSFER + ".*?\\}", "").trim();
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- 你目前处于 A2A (Agent-to-Agent) 协作模式。");
            sb.append("\n- **转交合规性**：当 Agent 发起移交请求时，请核对目标成员的“擅长技能”是否匹配，并确保移交指令不违背其“行为约束”。");
            sb.append("\n- **模态匹配**：若移交涉及图片或大文件，请确认目标成员具备相应的输入处理能力。");
        } else {
            sb.append("\n- You are in A2A (Agent-to-Agent) mode.");
            sb.append("\n- **Transfer Validation**: When an agent requests a transfer, verify if the target's 'Skills' match the task and ensure instructions do not violate their 'Constraints'.");
            sb.append("\n- **Modality Check**: Ensure the target can handle the required input modes (e.g., images/files) if applicable.");
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String lastAgent = trace.getLastAgentName();
        AgentTrace agentTrace = context.getAs("__" + lastAgent);

        if (agentTrace instanceof ReActTrace) {
            ReActTrace rt = (ReActTrace) agentTrace;
            ChatMessage lastMsg = rt.getMessages().get(rt.getMessages().size() - 1);
            if (lastMsg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) lastMsg;
                if (am.getToolCalls() != null) {
                    for (ToolCall tc : am.getToolCalls()) {
                        if (TOOL_TRANSFER.equals(tc.name())) {
                            // 提取并存储到上下文
                            String inst = getArg(tc.arguments(), "instruction");
                            String state = getArg(tc.arguments(), "state");
                            if (inst != null) trace.getProtocolContext().put(KEY_LAST_INSTRUCTION, inst);
                            if (state != null) trace.getProtocolContext().put(KEY_GLOBAL_STATE, state);

                            return getArg(tc.arguments(), "target");
                        }
                    }
                }
            }
        }
        return null;
    }

    private String getArg(Map args, String key) {
        return ONode.ofBean(args).get(key).getString();
    }

    // --- 阶段四：销毁与清理 ---
    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);
        trace.getProtocolContext().remove(KEY_GLOBAL_STATE);
        super.onTeamFinished(context, trace);
    }
}