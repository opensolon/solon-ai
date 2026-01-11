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
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 优化版层级化协作协议 (Hierarchical Protocol) - 增强多模态支持
 */
@Preview("3.8.1")
public class HierarchicalProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol.class);
    private static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    private static final String KEY_AGENT_USAGE = "agent_usage_map";

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

        public void absorb(String content, TeamProtocolBase protocol) {
            if (Utils.isEmpty(content)) return;

            ONode report = protocol.sniffJson(content);
            if (report.isObject() && !report.isEmpty()) {
                report.remove("agent");
                report.remove("role");
                data.setAll(report.getObjectUnsafe());
            } else {
                String memo = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                data.set("_last_memo", memo);
            }
        }

        @Override
        public String toString() { return data.toJson(); }
    }

    public HierarchicalProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "HIERARCHICAL"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();

        // 1. 注入汇报规范
        sb.append(isZh
                ? "\n### 汇报要求：\n- 请在回复结尾使用 JSON 块反馈核心数据（如：{\"result\": \"...\", \"status\": \"done\"}）。"
                : "\n### Reporting Requirement:\n- End your response with a JSON block for key data (e.g., {\"result\": \"...\", \"status\": \"done\"}).");

        // 2. 多模态适配：如果提示词中包含媒体附件，显式提醒专家
        if (originalPrompt.getMessages().stream()
                .filter(m->m.getRole()== ChatRole.USER)
                .map(m->(UserMessage)m)
                .anyMatch(m -> m.hasMedias())) {
            sb.append(isZh
                    ? "\n- **[重要]**：检测到输入中包含图片或文件，请务必结合附件内容进行处理。"
                    : "\n- **[IMPORTANT]**: Multimodal content (images/files) detected. Please process based on the attachments.");
        }

        // 调用基类逻辑合并上下文
        return super.prepareAgentPrompt(trace, agent, originalPrompt.addMessage(sb.toString()), locale);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext()
                .computeIfAbsent(KEY_HIERARCHY_STATE, k -> new HierarchicalState());

        String lastContent = trace.getLastAgentContent();

        if (Utils.isEmpty(lastContent) || lastContent.contains("Error:") || lastContent.contains("Exception:")) {
            state.markError(agent.name(), "Execution failed or empty response.");
        } else {
            state.clearError(agent.name());
            state.absorb(lastContent, this);
        }

        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
        usage.put(agent.name(), usage.getOrDefault(agent.name(), 0) + 1);

        super.onAgentEnd(trace, agent);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 团队运行看板 (Hierarchical Dashboard)\n" : "\n### Hierarchical Team Dashboard\n");

        ONode dashboard = (state != null) ? ONode.ofJson(state.toString()) : new ONode().asObject();
        ONode meta = dashboard.getOrNew("_meta");

        // 3. 模态能力看板注入：让 Supervisor 知道谁能看图/看文件
        ONode capabilities = meta.getOrNew("capabilities");
        config.getAgentMap().forEach((name, ag) -> {
            if(ag.profile() != null) {
                capabilities.set(name, ONode.ofBean(ag.profile().getInputModes()));
            }
        });

        if (usage != null && !usage.isEmpty()) {
            meta.set("agent_usage", usage);
        }

        if (dashboard.isEmpty()) {
            sb.append(isZh ? "> 暂无汇报数据，请下达初始指令。\n" : "> No data reported yet. Please issue initial instructions.\n");
        } else {
            sb.append("```json\n").append(dashboard.toJson()).append("\n```\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 管理决策准则：\n");
            sb.append("1. **模态适配**：若任务涉及图片或文件，请优先指派 `_meta.capabilities` 中包含相应模式的专家。\n");
            sb.append("2. **状态驱动**：根据看板中已有的数据决定下一步由谁补全缺失信息。\n");
            sb.append("3. **错误处理**：若 `_meta.errors` 存在记录，请指派另一位专家复核。\n");
            sb.append("4. **负载均衡**：避免单一专家过度疲劳。");
        } else {
            sb.append("\n### Management Guidelines:\n");
            sb.append("1. **Modality Match**: If the task involves images/files, prioritize agents with matching modes in `_meta.capabilities`.\n");
            sb.append("2. **State-Driven**: Decide the next step based on existing dashboard data.\n");
            sb.append("3. **Error Recovery**: If `_meta.errors` exists, re-assign or verify with a different expert.\n");
            sb.append("4. **Load Management**: Distribute tasks to maintain efficiency.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_HIERARCHY_STATE);
        trace.getProtocolContext().remove(KEY_AGENT_USAGE);
        super.onTeamFinished(context, trace);
    }
}