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
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 增强型顺序协作协议 (Sequential Protocol)
 *
 * 特点：
 * 1. 引入 SequenceState 进度看板。
 * 2. 自动化质量门禁 (Quality Gate)：不达标自动重试。
 * 3. 状态机驱动：由协议直接控制 Agent 的线性路由。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SequentialProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(SequentialProtocol.class);
    private static final String KEY_SEQUENCE_STATE = "sequence_state_obj";
    private int maxRetriesPerStage = 1;

    public static class SequenceState {
        private final List<String> pipeline = new ArrayList<>();
        private int currentIndex = 0;
        private final Map<String, StageInfo> stages = new LinkedHashMap<>();

        public static class StageInfo {
            public String status = "PENDING";
            public int retries = 0;
            public String summary;
        }

        public void init(Collection<String> agentNames) {
            pipeline.clear();
            pipeline.addAll(agentNames);
            agentNames.forEach(name -> stages.put(name, new StageInfo()));
        }

        public String getNextAgent() {
            return currentIndex < pipeline.size() ? pipeline.get(currentIndex) : Agent.ID_END;
        }

        public void markCurrent(String status, String summary) {
            if (currentIndex >= pipeline.size()) return;
            String name = pipeline.get(currentIndex);
            StageInfo info = stages.get(name);
            info.status = status;
            if (Utils.isNotEmpty(summary)) {
                info.summary = summary.length() > 50 ? summary.substring(0, 50) + "..." : summary;
            }
        }

        public void next() { currentIndex++; }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            root.set("progress", (currentIndex + 1) + "/" + pipeline.size());
            ONode stagesNode = root.getOrNew("stages").asArray();
            stages.forEach((k, v) -> {
                stagesNode.add(new ONode().asObject()
                        .set("agent", k)
                        .set("status", v.status)
                        .set("retries", v.retries));
            });
            return root.toJson();
        }
    }

    public SequentialProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "SEQUENTIAL"; }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        SequenceState state = (SequenceState) trace.getProtocolContext()
                .computeIfAbsent(KEY_SEQUENCE_STATE, k -> {
                    SequenceState s = new SequenceState();
                    s.init(config.getAgentMap().keySet());
                    return s;
                });

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 流水线执行状态 (Pipeline State)\n" : "\n### Pipeline State\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
    }

    @Override
    public boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        SequenceState state = (SequenceState) trace.getProtocolContext().get(KEY_SEQUENCE_STATE);
        if (state == null) return true;

        String next = state.getNextAgent();
        trace.setRoute(next);

        // 顺序协议由协议逻辑指定路由，不需要 LLM 介入决策
        return false;
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        SequenceState state = (SequenceState) trace.getProtocolContext().get(KEY_SEQUENCE_STATE);
        if (state == null) return;

        String content = trace.getLastAgentContent();
        boolean isSuccess = assessQuality(content);
        SequenceState.StageInfo info = state.stages.get(agent.name());

        if (isSuccess) {
            state.markCurrent("COMPLETED", content);
            state.next();
            LOG.info("Sequential Stage [{}] COMPLETED", agent.name());
        } else {
            if (info != null && info.retries < maxRetriesPerStage) {
                info.retries++;
                state.markCurrent("RETRYING", "Quality check failed, retrying...");
                LOG.warn("Sequential Stage [{}] RETRYING ({}/{})", agent.name(), info.retries, maxRetriesPerStage);
                // 注意：这里不执行 state.next()，Supervisor 会再次路由到当前 Agent
            } else {
                state.markCurrent("FAILED", "Max retries reached");
                state.next(); // 即使失败也跳过进入下一步，或根据业务需求 trace.setRoute(Agent.ID_END)
                LOG.error("Sequential Stage [{}] FAILED after {} retries", agent.name(), maxRetriesPerStage);
            }
        }
        super.onAgentEnd(trace, agent);
    }

    private boolean assessQuality(String content) {
        if (Utils.isEmpty(content)) return false;
        String upper = content.toUpperCase();
        // 排除常见的 AI 拒绝回复关键词
        if (upper.contains("CANNOT") || upper.contains("I'M SORRY") || upper.contains("FAILED")) return false;
        return content.trim().length() > 20;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_SEQUENCE_STATE);
        super.onTeamFinished(context, trace);
    }
}