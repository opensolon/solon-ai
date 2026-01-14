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
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A2A (Agent to Agent) 协作协议
 * * <p>核心机制：专家自主接力。Agent 可通过工具主动将任务及其上下文状态移交给另一位专家。
 * 协议内置流转次数审计（防止无限推诿）与多模态兼容性检查（防止能力不匹配）。</p>
 */
@Preview("3.8.1")
public class A2AProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(A2AProtocol.class);

    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_INSTRUCTION = "last_instruction";
    private static final String KEY_GLOBAL_STATE = "global_state";
    private static final String KEY_TRANSFER_COUNT = "transfer_count";

    private final int maxTransferRounds = 5; // 强制熔断阈值

    public A2AProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "A2A"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 初始拓扑：Start -> First Agent -> Supervisor -> ...
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 专家节点：完成后回归 Supervisor 判定接力信号
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns); // 动态路由
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    /**
     * 注入移交工具：动态生成除自身外的专家画像供 Agent 选择
     */
    @Override
    public void injectAgentTools(Agent agent, Consumer<FunctionTool> receiver) {
        Locale locale = config.getLocale();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        String expertsDescription = config.getAgentMap().entrySet().stream()
                .filter(e -> !e.getKey().equals(agent.name()))
                .map(e -> e.getKey() + " [" + e.getValue().profile().toFormatString(locale) + "]")
                .collect(Collectors.joining(" | "));

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_TRANSFER);
        tool.doHandle(args -> {
            String target = (String) args.get("target");
            return isZh ? "已发起向 [" + target + "] 的接力请求。" : "Transfer to [" + target + "] requested.";
        });

        if (isZh) {
            tool.title("任务移交")
                    .description("将任务移交给更合适的专家。")
                    .stringParamAdd("target", "目标专家名。必选: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "给接棒专家的指令。")
                    .stringParamAdd("state", "业务状态 JSON（全程持久化）。");
        } else {
            tool.title("Transfer")
                    .description("Hand over task to another expert.")
                    .stringParamAdd("target", "Expert name. Options: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "Specific instruction for the next expert.")
                    .stringParamAdd("state", "Persistent JSON state.");
        }
        receiver.accept(tool);
    }

    /**
     * 注入接力上下文：将上一个 Agent 的指令和全局状态合并入提示词
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        String instruction = (String) trace.getProtocolContext().get(KEY_LAST_INSTRUCTION);
        String state = (String) trace.getProtocolContext().get(KEY_GLOBAL_STATE);

        if (Utils.isEmpty(instruction) && Utils.isEmpty(state)) return originalPrompt;

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();
        sb.append(isZh ? "\n### 接力上下文 (Handover Context)\n" : "\n### Handover Context\n");

        if (Utils.isNotEmpty(instruction)) {
            sb.append("- ").append(isZh ? "**前序指令**: " : "**Prior Instruction**: ").append(instruction).append("\n");
        }
        if (Utils.isNotEmpty(state)) {
            sb.append("- ").append(isZh ? "**累积状态**: " : "**Global State**: ").append(state).append("\n");
        }

        List<ChatMessage> messages = new ArrayList<>(originalPrompt.getMessages());
        messages.add(ChatMessage.ofUser(sb.toString()));

        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION); // 消费指令
        return Prompt.of(messages);
    }

    /**
     * 解析接力信号：执行安全性校验并决定下一跳
     */
    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String lastAgentName = trace.getLastAgentName();
        AgentTrace agentTrace = context.getAs("__" + lastAgentName);

        if (agentTrace instanceof ReActTrace) {
            ReActTrace rt = (ReActTrace) agentTrace;
            AssistantMessage am = rt.getLastAssistantMessage();
            if (am != null && am.getToolCalls() != null) {
                for (ToolCall tc : am.getToolCalls()) {
                    if (TOOL_TRANSFER.equals(tc.name())) {
                        String target = getArg(tc.arguments(), "target");

                        // 1. 防推诿死循环检查
                        int count = (int) trace.getProtocolContext().getOrDefault(KEY_TRANSFER_COUNT, 0);
                        if (count >= maxTransferRounds) {
                            LOG.warn("A2A: Max transfer limit ({}) reached. Supervisor reclaiming control.", maxTransferRounds);
                            return null;
                        }

                        // 2. 模态兼容性硬检查：防止将识图任务移交给仅支持文本的专家
                        if (!checkModality(trace, target)) {
                            LOG.error("A2A: Transfer blocked. Target [{}] lacks vision capability for multimodal input.", target);
                            return null;
                        }

                        // 3. 持久化接力元数据
                        trace.getProtocolContext().put(KEY_LAST_INSTRUCTION, getArg(tc.arguments(), "instruction"));
                        trace.getProtocolContext().put(KEY_GLOBAL_STATE, getArg(tc.arguments(), "state"));
                        trace.getProtocolContext().put(KEY_TRANSFER_COUNT, count + 1);

                        if(LOG.isDebugEnabled()) {
                            LOG.debug("A2A: Handover authorized: {} -> {}", lastAgentName, target);
                        }
                        return target;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 模态能力匹配检查
     */
    private boolean checkModality(TeamTrace trace, String targetName) {
        Agent target = config.getAgentMap().get(targetName);
        if (target == null) return false;

        boolean hasMedia = trace.getPrompt().getMessages().stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(m -> (UserMessage) m)
                .anyMatch(UserMessage::hasMedias);

        if (hasMedia) {
            // 若原始任务带图，接力目标必须具备多模态能力
            return target.profile().getInputModes().stream().anyMatch(m -> !m.equalsIgnoreCase("text"));
        }
        return true;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        String state = (String) trace.getProtocolContext().get(KEY_GLOBAL_STATE);
        Integer count = (Integer) trace.getProtocolContext().get(KEY_TRANSFER_COUNT);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### A2A 接力看板\n" : "\n### A2A Dashboard\n");
        if (Utils.isNotEmpty(state)) {
            sb.append("- ").append(isZh ? "全局状态: " : "Global State: ").append(state).append("\n");
        }
        if (count != null) {
            sb.append("- ").append(isZh ? "接力次数: " : "Handovers: ").append(count).append("\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- **A2A 定标守则**：若流转超过 3 次仍无实质进展，请立即介入并强制收尾。");
        } else {
            sb.append("\n- **A2A Rules**: Intervene if more than 3 handovers occur without progress.");
        }
    }

    private String getArg(Map args, String key) {
        return ONode.ofBean(args).get(key).getString();
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);
        trace.getProtocolContext().remove(KEY_GLOBAL_STATE);
        trace.getProtocolContext().remove(KEY_TRANSFER_COUNT);
        super.onTeamFinished(context, trace);
    }
}