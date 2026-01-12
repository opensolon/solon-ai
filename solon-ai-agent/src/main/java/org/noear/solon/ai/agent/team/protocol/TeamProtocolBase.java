/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NodeSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@Preview("3.8.1")
public abstract class TeamProtocolBase implements TeamProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(TeamProtocolBase.class);

    protected final TeamAgentConfig config;

    public TeamProtocolBase(TeamAgentConfig config) {
        this.config = config;
    }

    protected void linkAgents(NodeSpec ns) {
        for (String agentName : config.getAgentMap().keySet()) {
            ns.linkAdd(agentName, l -> l.title("route = " + agentName).when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return agentName.equalsIgnoreCase(trace.getRoute());
            }));
        }
    }

    protected ONode sniffJson(String content) {
        if (content == null) return new ONode();
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start != -1 && end > start) {
            try {
                return ONode.ofJson(content.substring(start, end + 1));
            } catch (Exception e) {
                return new ONode();
            }
        }
        return new ONode();
    }

    protected List<String> getCandidateAgents(TeamTrace trace) {
        return config.getAgentMap().entrySet().stream()
                .filter(e -> e.getValue().profile() != null)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // 在 TeamProtocolBase 中增强
    protected boolean isLogicFinished(TeamTrace trace) {
        if (trace.getSteps().isEmpty()) {
            return false;
        }

        // 默认保护最后 1 个 Agent 必须参与
        return isLastNAgentsParticipated(trace, 1);
    }

    protected boolean isLastNAgentsParticipated(TeamTrace trace, int n) {
        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
        int size = agentNames.size();
        if (size <= 1) return true;

        // 获取最后 N 个 Agent
        List<String> requiredTail = agentNames.subList(Math.max(size - n, 0), size);

        Set<String> participated = trace.getSteps().stream()
                .map(s -> s.getSource().toLowerCase())
                .collect(Collectors.toSet());

        return requiredTail.stream().allMatch(name -> participated.contains(name.toLowerCase()));
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        if (trace == null || trace.isInitial()) return originalPrompt;

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        String profileDesc = (agent.profile() != null) ? agent.profile().toFormatString(locale) : "";

        // 1. 构建结构化的 System Context
        StringBuilder sb = new StringBuilder();
        if (isZh) {
            sb.append("# 任务上下文 (SYSTEM CONTEXT)\n");
            sb.append("## 你的身份\n").append(profileDesc).append("\n\n");
            sb.append("## 协作进度 (最近 5 轮)\n");
            sb.append(trace.getFormattedHistory(5, false)).append("\n\n");
            sb.append("---\n");
            sb.append("请根据上述进度，完成你负责的部分。");
        } else {
            sb.append("# SYSTEM CONTEXT\n");
            sb.append("## Your Identity\n").append(profileDesc).append("\n\n");
            sb.append("## Collaboration Progress (Last 5 steps)\n");
            sb.append(trace.getFormattedHistory(5, false)).append("\n\n");
            sb.append("---\n");
            sb.append("Please complete your task based on the progress above.");
        }

        Prompt newPrompt = new Prompt();
        // 关键点：System 消息承载重型背景，User 消息保持任务纯粹性
        newPrompt.addMessage(ChatMessage.ofSystem(sb.toString()));

        // 2. 注入特定约束（钩子）
        injectAgentConstraints(newPrompt, locale);

        // 3. 附加原始任务指令
        newPrompt.addMessage(originalPrompt.getMessages());
        return newPrompt;
    }

    protected void injectAgentConstraints(Prompt prompt, Locale locale) {
        // 由子类实现，如注入 JSON 输出格式要求
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 只有当决策包含“结束标识”时，才进行逻辑完备性检查
        if (decision.contains(config.getFinishMarker())) {

            // 如果用户定义了 graphAdjuster，表示进入编排模式，协议不再物理拦截
            if (config.getGraphAdjuster() != null) {
                return true;
            }

            // 检查逻辑是否完备（例如：末位 Agent 是否已参与）
            if (!isLogicFinished(trace)) {
                LOG.warn("Protocol [{}]: SOP requirements not met. Blocking [FINISH] signal.", name());
                // 此时拦截结束信号，强制返回 false，流程会重新回到 Supervisor 让它继续指派
                return false;
            }
        }
        return true;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        String candidates = String.join(", ", getCandidateAgents(null));

        if (isZh) {
            sb.append("\n## 协作协议：").append(name()).append("\n");
            sb.append("- 备选专家列表: [").append(candidates).append("]\n");
            sb.append("- 决策要求：必须匹配 Skills。如果任务已完成，输出 ").append(config.getFinishMarker());
        } else {
            sb.append("\n## Protocol: ").append(name()).append("\n");
            sb.append("- Candidate Experts: [").append(candidates).append("]\n");
            sb.append("- Requirement: Match tasks with Skills. If finished, output ").append(config.getFinishMarker());
        }
    }
}