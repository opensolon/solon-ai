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
 * 增强层级化协作协议 (Hierarchical Protocol)
 * * 特点：
 * 1. 引入 HierarchicalState，为 Supervisor 提供结构化进度看板。
 * 2. 自动化专家调用统计与负载感知。
 * 3. 简化专家建议逻辑，完全交由“状态数据”驱动。
 */
@Preview("3.8.1")
public class HierarchicalProtocol_H extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol_H.class);

    private static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    private static final String KEY_AGENT_USAGE = "agent_usage_map";

    /**
     * 层级协作专用状态：管理任务进度和专家反馈
     */
    public static class HierarchicalState {
        private final Map<String, Object> milestone = new LinkedHashMap<>();
        private final List<String> completedTasks = new ArrayList<>();

        public void update(String json) {
            if (Utils.isEmpty(json)) return;
            try {
                ONode node = ONode.ofJson(json);
                if (node.isObject()) {
                    node.getObjectUnsafe().forEach((k, v) -> {
                        if ("done".equalsIgnoreCase(k)) {
                            completedTasks.add(v.getString());
                        } else {
                            milestone.put(k, v.toBean());
                        }
                    });
                }
            } catch (Exception e) {
                milestone.put("_last_feedback", json);
            }
        }

        public boolean isEmpty() { return milestone.isEmpty() && completedTasks.isEmpty(); }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            milestone.forEach(root::set);
            if (!completedTasks.isEmpty()) {
                ONode doneNode = root.getOrNew("completed");
                completedTasks.forEach(doneNode::add);
            }
            return root.toJson();
        }
    }

    public HierarchicalProtocol_H(TeamConfig config) {
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
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        // 1. 结构化看板：展示已完成工作和关键结论
        sb.append(isZh ? "\n\n### 📊 任务进度看板 (Task Dashboard)\n" : "\n\n### 📊 Task Dashboard\n");
        if (state != null && !state.isEmpty()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
        } else {
            sb.append(isZh ? "> 等待专家首次汇报...\n" : "> Waiting for first report...\n");
        }

        // 2. 负载统计：辅助 Supervisor 判断谁比较闲
        if (usage != null && !usage.isEmpty()) {
            sb.append(isZh ? "\n**专家调用统计**: " : "\n**Agent Usage**: ").append(usage.toString()).append("\n");
        }
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        if (!Agent.ID_SUPERVISOR.equals(nextAgent) && !Agent.ID_END.equals(nextAgent)) {
            // 记录使用率
            Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                    .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
            usage.put(nextAgent, usage.getOrDefault(nextAgent, 0) + 1);
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 层级管控指令\n");
            sb.append("- **参考看板**：优先查看 JSON 中的 `completed` 列表，避免重复指派。\n");
            sb.append("- **负载均衡**：如果某专家调用次数过多，考虑是否有其他专家可替代。\n");
            sb.append("- **状态沉淀**：要求专家在汇报时提供结构化 JSON（包含 done 字段）。");
        } else {
            sb.append("\n## Hierarchical Rules\n");
            sb.append("- **Check Dashboard**: Look at the `completed` list to avoid redundant tasks.\n");
            sb.append("- **Balance Load**: If an agent is overused, consider alternatives.\n");
            sb.append("- **State Sync**: Ask experts to report in structured JSON with a 'done' field.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_HIERARCHY_STATE);
        trace.getProtocolContext().remove(KEY_AGENT_USAGE);
        super.onTeamFinished(context, trace);
    }
}