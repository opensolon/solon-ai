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
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
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
    private static final String KEY_LAST_TEMP_TARGET = "last_temp_target";
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
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        Locale locale = config.getLocale();
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        String expertsDescription = config.getAgentMap().entrySet().stream()
                .filter(e -> !e.getKey().equals(agent.name())) // 排除自己
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

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_TRANSFER);
        tool.doHandle(args -> {
            String target = (String) args.get("target");
            String instruction = (String) args.get("instruction");
            String state = (String) args.get("state");

            TeamTrace trace = context.getAs(Agent.KEY_CURRENT_TEAM_KEY);

            // 关键点：直接把解析好的参数存入协议上下文
            if(trace != null) {
                trace.getProtocolContext().put(KEY_LAST_TEMP_TARGET, target); // 标记本次要跳往的目标
                trace.getProtocolContext().put(KEY_LAST_INSTRUCTION, instruction);
                trace.getProtocolContext().put(KEY_GLOBAL_STATE, state);
            }

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

        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION); // 消费指令
        return Prompt.of(messages);
    }

    /**
     * 解析接力信号：执行安全性校验并决定下一跳
     */
    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String target = (String) trace.getProtocolContext().get(KEY_LAST_TEMP_TARGET);

        if (Utils.isNotEmpty(target)) {
            Locale locale = config.getLocale();
            boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
            trace.getProtocolContext().remove(KEY_LAST_TEMP_TARGET);

            // 1. 熔断检查
            int count = (int) trace.getProtocolContext().getOrDefault(KEY_TRANSFER_COUNT, 0);
            if (count >= maxTransferRounds) {
                if (isZh) {
                    trace.setFinalAnswer("协作达到最大流转次数，任务中止。");
                } else {
                    trace.setFinalAnswer("Max transfer limit reached.");
                }
                return null;
            }

            // 2. 核心校验
            Agent targetAgent = config.getAgentMap().get(target);
            if (targetAgent == null || !checkModality(trace, target)) {
                String feedback;
                if (isZh) {
                    feedback = "【系统通知】移交失败：专家 [" + target + "] 不存在或能力不匹配（如无法处理图片）。请重新选择或尝试自行解决。";
                } else {
                    feedback = "[System] Transfer failed: Expert [" + target + "] is invalid or lacks required capabilities. Please re-select or resolve yourself.";
                }

                // --- 关键适配点 ---
                // A. 记录一个系统步骤，让“黑匣子”轨迹完整
                trace.addStep(ChatRole.SYSTEM, "Supervisor", feedback, 0);

                // B. 注入到 Prompt 中，确保回退后的 Agent 在下一轮能看到这个 User 反馈
                List<ChatMessage> messages = new ArrayList<>(trace.getPrompt().getMessages());
                messages.add(ChatMessage.ofUser(feedback));
                trace.setPrompt(Prompt.of(messages));

                // C. 利用现有的 lastAgentName 实现精准回退
                String lastAgent = trace.getLastAgentName();
                LOG.warn("A2A: Invalid target [{}], bouncing back to [{}]", target, lastAgent);

                return lastAgent;
            }

            // 3. 校验通过
            trace.getProtocolContext().put(KEY_TRANSFER_COUNT, count + 1);
            return target;
        }

        return super.resolveSupervisorRoute(context, trace, decision);
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

        if (isZh) {
            sb.append("\n### A2A 接力看板\n");
            if (Utils.isNotEmpty(state)) sb.append("- 全局状态: ").append(state).append("\n");
            if (count != null) sb.append("- 接力次数: ").append(count).append("\n");
        } else {
            sb.append("\n### A2A Dashboard\n");
            if (Utils.isNotEmpty(state)) sb.append("- Global State: ").append(state).append("\n");
            if (count != null) sb.append("- Handovers: ").append(count).append("\n");
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
            sb.append("1. **Direct Delivery**: Output `" + config.getFinishMarker() + "` directly upon completion. **DO NOT** summarize or refine the expert's output.\n");
            sb.append("2. **Handover Audit**: Intervene and force termination if more than 3 handovers occur without progress.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_LAST_INSTRUCTION);
        trace.getProtocolContext().remove(KEY_LAST_TEMP_TARGET);
        trace.getProtocolContext().remove(KEY_GLOBAL_STATE);
        trace.getProtocolContext().remove(KEY_TRANSFER_COUNT);
        super.onTeamFinished(context, trace);
    }
}