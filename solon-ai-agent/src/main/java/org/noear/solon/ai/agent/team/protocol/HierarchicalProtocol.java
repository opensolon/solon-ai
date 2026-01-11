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
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 增强型层级化协作协议 (Hierarchical Protocol)
 *
 * 核心职责：
 * 1. 结构化看板：利用 ONode 自动吸收 Agent 的汇报数据。
 * 2. 状态驱动：Supervisor 基于实时更新的看板进行分发决策。
 * 3. 负载感知：统计成员调用频次并反馈至看板。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class HierarchicalProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol.class);

    private static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    private static final String KEY_AGENT_USAGE = "agent_usage_map";

    /**
     * 层级协作状态机 (基于 ONode 驱动)
     */
    public static class HierarchicalState {
        private final ONode data = new ONode().asObject();

        /**
         * 吸收 Agent 的反馈进入全局状态
         */
        public void absorb(String content) {
            if (Utils.isEmpty(content)) {
                return;
            }

            try {
                // 尝试解析 JSON 汇报
                ONode report = ONode.ofJson(content);
                if (report.isObject()) {
                    // 增量合并对象字段（Snack4 v4 风格）
                    data.setAll(report.getObjectUnsafe());
                } else {
                    data.set("_last_raw_memo", content);
                }
            } catch (Exception e) {
                // 处理非 JSON 输出，记录文本快照
                if (content.length() > 100) {
                    data.set("_last_text_memo", content.substring(0, 100) + "...");
                } else {
                    data.set("_last_text_memo", content);
                }
            }
        }

        public boolean isEmpty() {
            return data.isEmpty();
        }

        @Override
        public String toString() {
            return data.toJson();
        }
    }

    public HierarchicalProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "HIERARCHICAL";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 构建拓扑：Start -> Supervisor
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        // Supervisor 决策分支
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        // 专家节点执行完后回归 Supervisor 进行汇报
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 1. 同步专家状态到看板
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext()
                .computeIfAbsent(KEY_HIERARCHY_STATE, k -> new HierarchicalState());

        state.absorb(trace.getLastAgentContent());

        // 2. 统计专家调用负载
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
        usage.put(agent.name(), usage.getOrDefault(agent.name(), 0) + 1);

        LOG.debug("HierarchicalProtocol - State sync by agent: {}", agent.name());
        super.onAgentEnd(trace, agent);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 团队运行看板 (Team Dashboard)\n" : "\n### Team Dashboard\n");

        // 构建临时看板用于 Prompt 注入
        ONode dashboard = (state != null) ? ONode.ofJson(state.toString()) : new ONode().asObject();

        // 注入成员负载统计
        if (usage != null && !usage.isEmpty()) {
            dashboard.getOrNew("_agent_usage").setAll(usage);
        }

        if (dashboard.isEmpty()) {
            sb.append(isZh ? "> 初始状态，等待首个任务汇报。\n" : "> Initial state, waiting for the first report.\n");
        } else {
            sb.append("```json\n").append(dashboard.toJson()).append("\n```\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 层级协作增强规范：\n");
            sb.append("1. 状态优先：决策前请查阅看板 JSON，了解已完成的工作细节。\n");
            sb.append("2. 负载均衡：参考 _agent_usage 统计，避免过度依赖单一成员。\n");
            sb.append("3. 持续沉淀：指派专家时，要求其以结构化 JSON 反馈关键结论。");
        } else {
            sb.append("\n### Hierarchical Collaboration Guidelines:\n");
            sb.append("1. State-First: Always review the dashboard JSON before making decisions.\n");
            sb.append("2. Load Balancing: Consult _agent_usage to distribute tasks evenly.\n");
            sb.append("3. Persistence: Require agents to provide conclusions in structured JSON.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 清理 Context 资源
        trace.getProtocolContext().remove(KEY_HIERARCHY_STATE);
        trace.getProtocolContext().remove(KEY_AGENT_USAGE);
        super.onTeamFinished(context, trace);
    }
}