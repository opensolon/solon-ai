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

/**
 * 团队协议基类 (Team Protocol Base)
 * * <p>核心职责：提供拓扑构建工具、Prompt 结构化封装、以及 SOP 执行完备性检查。</p>
 */
@Preview("3.8.1")
public abstract class TeamProtocolBase implements TeamProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(TeamProtocolBase.class);

    protected final TeamAgentConfig config;

    public TeamProtocolBase(TeamAgentConfig config) {
        this.config = config;
    }

    /**
     * 自动构建 Flow 节点间的动态链接
     */
    protected void linkAgents(NodeSpec ns) {
        for (String agentName : config.getAgentMap().keySet()) {
            ns.linkAdd(agentName, l -> l.title("route = " + agentName).when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return agentName.equalsIgnoreCase(trace.getRoute());
            }));
        }
    }

    /**
     * 从文本内容中提取第一个 JSON 对象（常用于解析结构化输出）
     */
    protected ONode sniffJson(String content) {
        if (content == null) return new ONode();
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start != -1 && end > start) {
            try {
                return ONode.ofJson(content.substring(start, end + 1));
            } catch (Throwable e) {
                LOG.warn("sniffJson failed: invalid json block");
                return new ONode();
            }
        }
        return new ONode();
    }

    /**
     * 获取候选 Agent 列表（含无 profile 成员，与可路由集合对齐）。
     * 无 profile 的专家仍可被调度，不应从候选提示中抹掉。
     */
    protected List<String> getCandidateAgents(TeamTrace trace) {
        return new ArrayList<>(config.getAgentMap().keySet());
    }

    /**
     * SOP 完备性检查。
     * <p>默认：至少有一名专家产出记录且已发生过决策轮次；
     * 不再强制「注册顺序末位 N 个 Agent 必须参与」——该规则对
     * Hierarchical / Swarm 等非线性调度过硬，各协议可覆盖本方法。</p>
     */
    protected boolean isLogicFinished(TeamTrace trace) {
        if (trace == null || trace.getRecords() == null || trace.getRecords().isEmpty()) {
            return false;
        }
        if (trace.getTurnCount() < 1) {
            return false;
        }
        // 至少一名成员专家产出过内容（排除纯系统/主管记录）
        return trace.getRecords().stream().anyMatch(TeamTrace.TeamRecord::isAgent);
    }

    /**
     * 检查末尾 N 个 Agent 是否已参与协作
     */
    protected boolean isLastNAgentsParticipated(TeamTrace trace, int n) {
        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
        int size = agentNames.size();
        if (size <= 1) return true;

        List<String> requiredTail = agentNames.subList(Math.max(size - n, 0), size);
        Set<String> participated = trace.getRecords().stream()
                .map(s -> s.getSource().toLowerCase())
                .collect(Collectors.toSet());

        return requiredTail.stream().allMatch(name -> participated.contains(name.toLowerCase()));
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        TeamTrace trace = TeamTrace.getCurrent(context);
        if(trace == null){
            return;
        }

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        String profileDesc = (agent.profile() != null) ? agent.profile().toFormatString(locale) : "";
        int windowSize = 5;
        if (trace.getOptions() != null) {
            windowSize = Math.max(1, trace.getOptions().getRecordWindowSize());
        }

        if (isZh) {
            sb.append("## 任务上下文 (SYSTEM CONTEXT)\n");
            sb.append("### 你的身份\n").append(profileDesc).append("\n\n");
            sb.append("### 协作进度 (最近 ").append(windowSize).append(" 条)\n");
            sb.append(trace.getFormattedHistory(windowSize, false)).append("\n\n");
            sb.append("---\n请根据上述进度完成你的任务。");
        } else {
            sb.append("## SYSTEM CONTEXT\n");
            sb.append("### Your Identity\n").append(profileDesc).append("\n\n");
            sb.append("### Collaboration Records (Last ").append(windowSize).append(" entries)\n");
            sb.append(trace.getFormattedHistory(windowSize, false)).append("\n\n");
            sb.append("---\nPlease complete your task based on the progress.");
        }
    }

    /**
     * 默认返回 {@link Prompt#copy()} 副本。
     * 入参通常是共享 workingMemory；子类若要追加协议消息，请先 super 再增强，禁止原地修改。
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        return originalPrompt == null ? Prompt.of() : originalPrompt.copy();
    }

    /**
     * 路由守卫：拦截过早的结束信号，确保流程满足 SOP 完备性
     */
    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            // 编排模式下（graphAdjuster 有值）不进行物理拦截，由用户自行控制
            if (config.getGraphAdjuster() != null) return true;

            if (!isLogicFinished(trace)) {
                LOG.warn("Protocol [{}]: SOP requirements not met (no agent output yet). Blocking [FINISH] signal.", name());
                return false; // 返回 false 强制 Supervisor 继续指派，不能结束
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
            sb.append("- 备选成员: [").append(candidates).append("]\n");
            sb.append("- 决策要求：任务完成请输出 ").append(config.getFinishMarker());
        } else {
            sb.append("\n## Collaboration Protocol: ").append(name()).append("\n");
            sb.append("- Candidates: [").append(candidates).append("]\n");
            sb.append("- Requirement: Output ").append(config.getFinishMarker()).append(" when finished.");
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (config.getAgentMap().containsKey(decision)) {
            return decision;
        }

        return null; // 返回 null，触发 SupervisorTask 的模糊匹配兜底
    }
}