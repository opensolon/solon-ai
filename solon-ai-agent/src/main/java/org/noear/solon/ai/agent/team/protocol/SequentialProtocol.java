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
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * 顺序协作协议 (Sequential Protocol)
 *
 * <p>核心特征：按照成员定义的物理顺序进行线性调度。具备“质量门禁”和“模态安全检查”机制，
 * 能够根据上下文数据类型（如图像）自动跳过不兼容的成员。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SequentialProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SequentialProtocol.class);
    private static final String KEY_SEQUENCE_STATE = "sequence_state_obj";
    private int maxRetriesPerStage = 1;
    private boolean stopOnFailure = false;

    public SequentialProtocol(TeamAgentConfig config) { super(config); }

    // 获取状态看板，增加 sync 逻辑（为了断点续传）
    public SequenceState getSequenceState(TeamTrace trace) {
        return (SequenceState) trace.getProtocolContext().computeIfAbsent(KEY_SEQUENCE_STATE, k -> {
            SequenceState s = new SequenceState();
            s.init(trace);
            return s;
        });
    }

    @Override
    public String name() { return "SEQUENTIAL"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_HANDOVER);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_HANDOVER));

        spec.addActivity(new SequentialTask(config, this)).then(ns -> {
            linkAgents(ns);
            ns.linkAdd(Agent.ID_END);
        });

        spec.addEnd(Agent.ID_END);
    }

    // --- 保持旧代码逻辑：质量检查、进度展示 ---

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        SequenceState state = getSequenceState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 流水线进度 (Pipeline Progress)\n" : "\n### Pipeline Progress\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        SequenceState state = getSequenceState(trace);
        String content = trace.getLastAgentContent();

        // 质量评估
        boolean isSuccess = assessQuality(content);
        StageInfo info = state.stages.get(agent.name());

        if (isSuccess) {
            state.markCurrent("COMPLETED", content);
            state.next();
            // 成功后，强制路由到中转 Task 寻找下一个
            trace.setRoute(Agent.ID_HANDOVER);
        } else {
            if (info != null && info.retries < maxRetriesPerStage) {
                info.retries++;
                state.markCurrent("RETRYING", "Quality check failed");
                trace.setRoute(agent.name()); // 触发重试
            } else {
                state.markCurrent("FAILED", "Quality check failed after retries");
                if (stopOnFailure) {
                    trace.setRoute(Agent.ID_END);
                } else {
                    state.next(); // 跳过，继续走
                    trace.setRoute(Agent.ID_HANDOVER);
                }
            }
        }
        super.onAgentEnd(trace, agent);
    }

    public boolean detectMediaPresence(TeamTrace trace) {
        String content = trace.getLastAgentContent();
        if (content == null) return false;
        return content.contains("![image]") || content.matches("(?s).*\\[.*?\\]\\(data:image/.*\\).*");
    }

    private boolean assessQuality(String content) {
        if (Utils.isEmpty(content)) return false;
        return content.trim().length() > 2; // 降低门槛
    }

    // --- 内部状态看板 ---

    public static class StageInfo {
        public String status = "PENDING";
        public int retries = 0;
        public String summary;
    }

    public static class SequenceState {
        private final List<String> pipeline = new ArrayList<>();
        private int currentIndex = 0;
        private final Map<String, StageInfo> stages = new LinkedHashMap<>();

        public void init(TeamTrace trace) {
            Collection<String> agentNames = trace.getConfig().getAgentMap().keySet();
            this.pipeline.clear();
            this.pipeline.addAll(agentNames);
            agentNames.forEach(name -> stages.put(name, new StageInfo()));

            if (trace != null && trace.getRecords() != null) {
                long completedCount = trace.getRecords().stream()
                        .filter(r -> r.isAgent() && pipeline.contains(r.getSource()))
                        .map(TeamTrace.TeamRecord::getSource)
                        .distinct()
                        .count();

                this.currentIndex = (int) completedCount;
            }
        }

        public String getNextAgent() {
            return currentIndex < pipeline.size() ? pipeline.get(currentIndex) : Agent.ID_END;
        }

        public void markCurrent(String status, String summary) {
            if (currentIndex < pipeline.size()) {
                StageInfo info = stages.get(pipeline.get(currentIndex));
                if (info != null) {
                    info.status = status;
                    if (Utils.isNotEmpty(summary)) {
                        info.summary = summary.length() > 50 ? summary.substring(0, 50) + "..." : summary;
                    }
                }
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
}