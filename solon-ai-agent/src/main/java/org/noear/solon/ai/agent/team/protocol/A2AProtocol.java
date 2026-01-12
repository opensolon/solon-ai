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
 * A2A (Agent to Agent) 协作协议增强版
 * * 优化：
 * 1. 增加 transfer_count 计数，防止推诿死循环。
 * 2. 强化模态硬校验，确保接棒专家具备处理附件的能力。
 * 3. 优化 Prompt 消息流，增强指令遵循度。
 */
@Preview("3.8.1")
public class A2AProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(A2AProtocol.class);

    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_INSTRUCTION = "last_instruction";
    private static final String KEY_GLOBAL_STATE = "global_state";
    private static final String KEY_TRANSFER_COUNT = "transfer_count";

    private final int maxTransferRounds = 5; // 最大转交次数

    public A2AProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "A2A"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        // A2A 模式通常由首个 Agent 启动或主管分配
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 所有专家执行完归队给 Supervisor 解析接力信号
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns); // 执行动态接力或结束
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = config.getLocale();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        // 动态构建除自己以外的专家画像
        String expertsDescription = config.getAgentMap().entrySet().stream()
                .filter(e -> !e.getKey().equals(agent.name()))
                .map(e -> e.getKey() + " [" + e.getValue().profile().toFormatString(locale) + "]")
                .collect(Collectors.joining(" | "));

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_TRANSFER);

        tool.doHandle(args -> {
            String target = (String) args.get("target");
            return isZh ? "已发起向 [" + target + "] 的任务转交，请等待主管确认。"
                    : "Transfer request to [" + target + "] sent. Waiting for supervisor.";
        });

        if (isZh) {
            tool.title("任务移交")
                    .description("将当前任务移交给团队中更合适的专家接力。")
                    .stringParamAdd("target", "目标专家名。必选: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "给接棒专家的具体作业指令。")
                    .stringParamAdd("state", "需要传递的业务状态 JSON（持久化直到任务结束）。");
        } else {
            tool.title("Transfer")
                    .description("Transfer current task to a more suitable expert.")
                    .stringParamAdd("target", "Target expert name. Options: [" + expertsDescription + "]")
                    .stringParamAdd("instruction", "Specific instruction for the next expert.")
                    .stringParamAdd("state", "JSON state to persist across handovers.");
        }
        trace.addProtocolTool(tool);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        String instruction = (String) trace.getProtocolContext().get(KEY_LAST_INSTRUCTION);
        String state = (String) trace.getProtocolContext().get(KEY_GLOBAL_STATE);

        if (Utils.isEmpty(instruction) && Utils.isEmpty(state)) {
            return originalPrompt;
        }

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();
        sb.append(isZh ? "\n### 任务接力上下文 (Handover Context)\n" : "\n### Handover Context\n");

        if (Utils.isNotEmpty(instruction)) {
            sb.append("- ").append(isZh ? "**接棒指令**: " : "**Instruction**: ").append(instruction).append("\n");
        }
        if (Utils.isNotEmpty(state)) {
            sb.append("- ").append(isZh ? "**当前状态**: " : "**Current State**: ").append(state).append("\n");
        }
        sb.append("\n---\n");

        // 将接力上下文注入到消息流中，确保其作为 User 提示的头部
        List<ChatMessage> messages = new ArrayList<>(originalPrompt.getMessages());
        messages.add(ChatMessage.ofUser(sb.toString()));

        // 消费瞬时指令
        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);

        return Prompt.of(messages);
    }

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

                        // 1. 死循环防护
                        int count = (int) trace.getProtocolContext().getOrDefault(KEY_TRANSFER_COUNT, 0);
                        if (count >= maxTransferRounds) {
                            LOG.warn("A2A - Max transfers reached. Supervisor taking control.");
                            return null;
                        }

                        // 2. 模态安全校验
                        if (!checkModality(trace, target)) {
                            LOG.warn("A2A - Target {} does not support multimodal input in context.", target);
                            // 这里可以决定是拦截报错还是打回 Supervisor
                            return null;
                        }

                        // 3. 提取并暂存接力数据
                        trace.getProtocolContext().put(KEY_LAST_INSTRUCTION, getArg(tc.arguments(), "instruction"));
                        trace.getProtocolContext().put(KEY_GLOBAL_STATE, getArg(tc.arguments(), "state"));
                        trace.getProtocolContext().put(KEY_TRANSFER_COUNT, count + 1);

                        return target;
                    }
                }
            }
        }
        return null;
    }

    private boolean checkModality(TeamTrace trace, String targetName) {
        Agent target = config.getAgentMap().get(targetName);
        if (target == null) return false;

        // 检查原始 Prompt 中是否包含多模态内容
        boolean hasMedia = trace.getPrompt().getMessages().stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(m -> (UserMessage) m)
                .anyMatch(UserMessage::hasMedias);

        if (hasMedia) {
            // 如果任务有图，接棒者必须支持除 text 以外的模式
            return target.profile().getInputModes().stream().anyMatch(m -> !m.equalsIgnoreCase("text"));
        }
        return true;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        String state = (String) trace.getProtocolContext().get(KEY_GLOBAL_STATE);
        Integer count = (Integer) trace.getProtocolContext().get(KEY_TRANSFER_COUNT);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### A2A 接力看板\n" : "\n### A2A Transfer Dashboard\n");
        if (Utils.isNotEmpty(state)) {
            sb.append("- ").append(isZh ? "全局状态: " : "Global State: ").append(state).append("\n");
        }
        if (count != null) {
            sb.append("- ").append(isZh ? "流转次数: " : "Transfer Count: ").append(count).append("\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- **A2A 定标守则**：Agent 之间的自主转交需符合业务逻辑；若发生 3 次以上转交未果，请强制干预并定稿。");
        } else {
            sb.append("\n- **A2A Awarding Rules**: Ensure agent handovers align with logic; intervene if >3 transfers occur without progress.");
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