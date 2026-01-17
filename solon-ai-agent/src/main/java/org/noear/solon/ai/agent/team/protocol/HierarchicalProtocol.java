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
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 层级化协作协议 (Hierarchical Protocol)
 *
 * <p>核心特征：引入“运行看板”机制。各成员的产出被结构化提取并呈现在状态看板中，
 * Supervisor 依据看板进度、成员负载及错误记录进行全局调度。</p>
 */
@Preview("3.8.1")
public class HierarchicalProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol.class);
    private static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    private static final String KEY_AGENT_USAGE = "agent_usage_map";

    /**
     * 协作状态机：负责汇报数据的吸收、错误记录与状态持久化
     */
    public static class HierarchicalState {
        private final ONode data = new ONode().asObject();

        public void markError(String agentName, String error) {
            data.getOrNew("_meta").getOrNew("errors").set(agentName, error);
        }

        public void clearError(String agentName) {
            if (data.hasKey("_meta")) {
                data.get("_meta").get("errors").remove(agentName);
            }
        }

        /**
         * 吸收成员产出：若为 JSON 则结构化合并，若为文本则记录到报告区
         */
        public void absorb(String agentName, String content, TeamProtocolBase protocol) {
            if (Utils.isEmpty(content)) return;

            ONode report = protocol.sniffJson(content);
            if (report.isObject() && !report.isEmpty()) {
                report.remove("agent");
                report.remove("role");
                data.setAll(report.getObjectUnsafe());
            } else {
                // 文本摘要处理，防止 Supervisor 被长篇幅汇报干扰
                String memo = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                data.getOrNew("_reports").set(agentName, memo);
            }
        }

        @Override
        public String toString() { return data.toJson(); }
    }

    public HierarchicalProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "HIERARCHICAL"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 固定拓扑：Start -> Supervisor <-> Agents -> End
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    /**
     * 增强专家指令：注入汇报规范与多模态提醒
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        ChatMessage lastMessage = originalPrompt.getLastMessage();
        if(lastMessage != null && lastMessage.getContent() != null && lastMessage.getContent().contains("### 汇报要求：")){
            // 已经包含汇报要求，则不重复添加
            return super.prepareAgentPrompt(trace, agent, originalPrompt, locale);
        }


        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();

        // 1. 强制汇报规范
        sb.append(isZh
                ? "\n### 汇报要求：\n- 请在回复结尾使用 JSON 块反馈核心数据（例：{\"result\": \"...\", \"status\": \"done\"}）。"
                : "\n### Reporting Requirement:\n- End response with a JSON block for key data (e.g., {\"result\": \"...\", \"status\": \"done\"}).");

        // 2. 多模态内容感知：检测输入是否存在媒体附件
        if (originalPrompt.getMessages().stream()
                .filter(m->m.getRole()== ChatRole.USER)
                .map(m->(UserMessage)m)
                .anyMatch(UserMessage::hasMedias)) {
            sb.append(isZh
                    ? "\n- **[重要]**：输入包含多媒体附件，请务必结合视觉/文件内容进行处理。"
                    : "\n- **[IMPORTANT]**: Multimodal content detected. Process based on the attachments.");
        }

        return super.prepareAgentPrompt(trace, agent, originalPrompt.addMessage(sb.toString()), locale);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext()
                .computeIfAbsent(KEY_HIERARCHY_STATE, k -> new HierarchicalState());

        String lastContent = trace.getLastAgentContent();

        // 执行审计：记录异常或吸收成果
        if (Utils.isEmpty(lastContent) || lastContent.contains("Error:") || lastContent.contains("Exception:")) {
            state.markError(agent.name(), "Execution failed or empty response.");
            LOG.warn("Hierarchical: Agent [{}] execution flagged with error.", agent.name());
        } else {
            state.clearError(agent.name());
            state.absorb(agent.name(), lastContent, this);
        }

        // 统计成员调用频次，辅助负载均衡
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
        usage.put(agent.name(), usage.getOrDefault(agent.name(), 0) + 1);

        super.onAgentEnd(trace, agent);
    }

    /**
     * 实时构建运行看板：注入成员能力、负载与错误信息
     */
    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 运行看板 (Hierarchical Dashboard)\n" : "\n### Hierarchical Dashboard\n");

        ONode dashboard = (state != null) ? ONode.ofJson(state.toString()) : new ONode().asObject();
        ONode meta = dashboard.getOrNew("_meta");

        // 注入模态能力矩阵，辅助 Supervisor 选人
        ONode capabilities = meta.getOrNew("capabilities");
        config.getAgentMap().forEach((name, ag) -> {
            if(ag.profile() != null) {
                capabilities.set(name, ONode.ofBean(ag.profile().getInputModes()));
            }
        });

        if (usage != null && !usage.isEmpty()) { meta.set("agent_usage", usage); }

        if (dashboard.isEmpty()) {
            sb.append(isZh ? "> 暂无汇报，请下达初始指令。\n" : "> No reports yet. Issue instructions.\n");
        } else {
            sb.append("```json\n").append(dashboard.toJson()).append("\n```\n");
        }

        if (isZh) {
            sb.append("\n> **决策指引**：对比看板进度。若关键专家未产出或存在错误记录，请继续调度，严禁提前结束。\n");
        } else {
            sb.append("\n> **Decision Hint**: Check progress. Do not end if key experts are missing or errors exist.\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 管理决策准则：\n");
            sb.append("1. **模态适配**：优先指派支持对应模式（图片/文件）的专家。\n");
            sb.append("2. **错误恢复**：若 `_meta.errors` 存在，请指派其他专家复核或重试。\n");
            sb.append("3. **负载均衡**：参考 `agent_usage` 避免过度使用单一专家。");
        } else {
            sb.append("\n### Management Guidelines:\n");
            sb.append("1. **Modality Match**: Assign experts based on capabilities.\n");
            sb.append("2. **Error Recovery**: Handle `_meta.errors` by re-assignment.\n");
            sb.append("3. **Load Balance**: Use `agent_usage` for efficient distribution.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 协作结束，清理内存上下文
        trace.getProtocolContext().remove(KEY_HIERARCHY_STATE);
        trace.getProtocolContext().remove(KEY_AGENT_USAGE);
        super.onTeamFinished(context, trace);
    }
}