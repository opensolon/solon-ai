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

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A2A (Agent to Agent) 协作协议
 * * <p>核心机制：专家自主接力。Agent 可通过工具主动将任务及其上下文状态移交给另一位专家。</p>
 */
@Preview("3.8.1")
public class A2AProtocol extends TeamProtocolBase {
    private static final String KEY_A2A_STATE = "a2a_state_obj";
    private static final String TOOL_TRANSFER = "__transfer_to__";
    private final int maxTransferRounds = 5;

    public A2AProtocol(TeamAgentConfig config) { super(config); }

    public static class A2AState {
        private String globalState = "";
        private int transferCount = 0;
        private String lastInstruction = "";
        private String lastTempTarget = "";

        public void setTransferRequest(String target, String instruction, String state) {
            this.lastTempTarget = target;
            this.lastInstruction = instruction;
            if (Utils.isNotEmpty(state)) {
                this.globalState = state;
            }
        }

        public void confirmTransfer() {
            this.transferCount++;
            this.lastTempTarget = "";
        }

        public void consumeInstruction() { this.lastInstruction = ""; }
        public String getGlobalState() { return globalState; }
        public int getTransferCount() { return transferCount; }
        public String getLastInstruction() { return lastInstruction; }
        public String getLastTempTarget() { return lastTempTarget; }
    }

    public A2AState getA2AState(TeamTrace trace) {
        return (A2AState) trace.getProtocolContext().computeIfAbsent(KEY_A2A_STATE, k -> new A2AState());
    }

    @Override
    public String name() { return "A2A"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(A2AHandoverTask.ID_HANDOVER));

        spec.addActivity(new A2AHandoverTask(config, this)).then(ns -> {
            linkAgents(ns);
            ns.linkAdd(TeamAgent.ID_SUPERVISOR);
        });

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        TeamTrace trace = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        if (trace == null) return;

        A2AState stateObj = getA2AState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        String expertsDescription = config.getAgentMap().entrySet().stream()
                .filter(e -> !e.getKey().equals(agent.name()))
                .map(e -> {
                    Agent expert = e.getValue();
                    String skills = String.join(",", expert.profile().getSkills());
                    String modes = String.join(",", expert.profile().getInputModes());
                    String desc = expert.description();

                    if (isZh) {
                        return String.format("%s(%s) [技能:%s | 模态:%s]", e.getKey(), desc, skills, modes);
                    } else {
                        return String.format("%s(%s) [Skills:%s | Modes:%s]", e.getKey(), desc, skills, modes);
                    }
                })
                .collect(Collectors.joining(" | "));

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_TRANSFER).returnDirect(true);
        tool.doHandle(args -> {
            String target = (String) args.get("target");
            String instruction = (String) args.get("instruction");
            String state = (String) args.get("state");

            stateObj.setTransferRequest(target, instruction, state);

            if (isZh) {
                return "已发起向 [" + target + "] 的接力请求。";
            } else {
                return "Transfer to [" + target + "] requested.";
            }
        });

        if (isZh) {
            tool.title("任务移交")
                    .description("当你无法完成当前任务时，将其移交给更合适的专家。")
                    .stringParamAdd("target", "目标专家名。必选范围: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "给接棒专家的具体执行指令。")
                    .stringParamAdd("state", "业务状态 JSON。");
        } else {
            tool.title("Transfer")
                    .description("When you are unable to complete the current task, hand it over to a more appropriate expert.")
                    .stringParamAdd("target", "Expert name. Options: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "Specific instruction for the next expert.")
                    .stringParamAdd("state", "Persistent JSON state.");
        }

        receiver.accept(tool);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 专家协作指引\n");
            sb.append("- 必须根据各专家的 [技能] 和 [模态] 标签精准选择目标，严禁向不支持相关模态的专家移交任务。\n");
        } else {
            sb.append("\n## Collaboration Guidelines\n");
            sb.append("- You must choose the target precisely based on [Skills] and [Modes] tags. Never transfer to an agent that lacks the required modality.\n");
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        A2AState stateObj = getA2AState(trace);
        String instruction = stateObj.getLastInstruction();
        String state = stateObj.getGlobalState();

        if (Utils.isEmpty(instruction) && Utils.isEmpty(state)) return originalPrompt;

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();
        if (isZh) {
            sb.append("\n### 接力上下文 (Handover Context)\n");
            if (Utils.isNotEmpty(instruction)) sb.append("- **前序指令**: ").append(instruction).append("\n");
            if (Utils.isNotEmpty(state)) sb.append("- **累积状态**: ").append(state).append("\n");
        } else {
            sb.append("\n### Handover Context\n");
            if (Utils.isNotEmpty(instruction)) sb.append("- **Prior Instruction**: ").append(instruction).append("\n");
            if (Utils.isNotEmpty(state)) sb.append("- **Global State**: ").append(state).append("\n");
        }

        List<ChatMessage> messages = new ArrayList<>(originalPrompt.getMessages());
        messages.add(ChatMessage.ofUser(sb.toString()));

        stateObj.consumeInstruction();
        return Prompt.of(messages);
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        A2AState stateObj = getA2AState(trace);
        String target = stateObj.getLastTempTarget();

        if (Utils.isNotEmpty(target)) {
            Locale locale = config.getLocale();
            boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

            // 1. 熔断检查
            if (stateObj.getTransferCount() >= maxTransferRounds) {
                if (isZh) {
                    trace.setFinalAnswer("协作达到最大流转次数，任务中止。");
                } else {
                    trace.setFinalAnswer("Max transfer limit reached.");
                }
                return null;
            }

            Agent targetAgent = config.getAgentMap().get(target);
            if (targetAgent == null || !checkModality(trace, target)) {
                stateObj.confirmTransfer();

                String feedback;
                if (isZh) {
                    feedback = "【系统通知】移交失败：专家 [" + target + "] 不存在或能力不匹配（如无法处理图片）。请重新选择或尝试自行解决。";
                } else {
                    feedback = "[System] Transfer failed: Expert [" + target + "] is invalid or lacks required capabilities. Please re-select or resolve yourself.";
                }

                trace.addRecord(ChatRole.SYSTEM, "Supervisor", feedback, 0);

                List<ChatMessage> messages = new ArrayList<>(trace.getPrompt().getMessages());
                messages.add(ChatMessage.ofUser(feedback));
                trace.setPrompt(Prompt.of(messages));

                return trace.getLastAgentName();
            }

            // 3. 校验通过
            stateObj.confirmTransfer();
            return target;
        }

        return super.resolveSupervisorRoute(context, trace, decision);
    }

    private boolean checkModality(TeamTrace trace, String targetName) {
        Agent target = config.getAgentMap().get(targetName);
        if (target == null) return false;

        boolean hasMedia = trace.getPrompt().getMessages().stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(m -> (UserMessage) m)
                .anyMatch(UserMessage::hasMedias);

        return !hasMedia || target.profile().getInputModes().stream().anyMatch(m -> !m.equalsIgnoreCase("text"));
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        A2AState stateObj = getA2AState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        if (stateObj != null) {
            if (isZh) {
                sb.append("\n### A2A 接力看板\n");
                if (Utils.isNotEmpty(stateObj.getGlobalState())) {
                    sb.append("- 全局状态: ").append(stateObj.getGlobalState()).append("\n");
                }
                sb.append("- 接力次数: ").append(stateObj.getTransferCount()).append("\n");
            } else {
                sb.append("\n### A2A Dashboard\n");
                if (Utils.isNotEmpty(stateObj.getGlobalState())) {
                    sb.append("- Global State: ").append(stateObj.getGlobalState()).append("\n");
                }
                sb.append("- Handovers: ").append(stateObj.getTransferCount()).append("\n");
            }
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        if (isZh) {
            sb.append("\n### A2A 协作准则：\n");
            sb.append("1. **直接交付**：任务完成时请直接输出 `" + config.getFinishMarker() + "`，**禁止**对专家的内容进行二次总结或润色。\n");
            sb.append("2. **流转审计**：若专家间接力流转超过 3 次仍无实质进展，请立即介入并强制收尾。");
        } else {
            sb.append("\n### A2A Collaboration Rules:\n");
            sb.append("1. **Direct Delivery**: Output `" + config.getFinishMarker() + "` directly. **DO NOT** summarize.\n");
            sb.append("2. **Handover Audit**: Force termination if more than 3 handovers occur.");
        }
    }
}