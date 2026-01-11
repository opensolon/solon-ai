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
 * * 特点：
 * 1. 引入 SequenceState 进度看板，实时展示流水线健康度。
 * 2. 自动化的质量门禁 (Quality Gate)：若上一环节输出不达标，自动触发重试。
 * 3. 简化路由逻辑：由状态机控制 Agent 的线性推演。
 */
@Preview("3.8.1")
public class SequentialProtocol_H extends HierarchicalProtocol_H {
    private static final Logger LOG = LoggerFactory.getLogger(SequentialProtocol_H.class);

    private static final String KEY_SEQUENCE_STATE = "sequence_state_obj";
    private int maxRetriesPerStage = 1;

    /**
     * 流水线状态内部类
     */
    public static class SequenceState {
        private final List<String> pipeline = new ArrayList<>();
        private int currentIndex = 0;
        private final Map<String, StageInfo> stages = new LinkedHashMap<>();

        public static class StageInfo {
            public String status = "PENDING"; // PENDING, RUNNING, COMPLETED, FAILED
            public int retries = 0;
            public String summary;
        }

        public void init(Collection<String> agentNames) {
            pipeline.addAll(agentNames);
            agentNames.forEach(name -> stages.put(name, new StageInfo()));
        }

        public String getNextAgent() {
            return currentIndex < pipeline.size() ? pipeline.get(currentIndex) : Agent.ID_END;
        }

        public void markCurrent(String status, String summary) {
            String name = pipeline.get(currentIndex);
            StageInfo info = stages.get(name);
            info.status = status;
            if (summary.length() > 50) {
                info.summary = summary.substring(0, 50) + "..."; // 摘要截断
            } else {
                info.summary = summary;
            }
        }

        public void next() { currentIndex++; }

        @Override
        public String toString() {
            ONode root = new ONode();
            root.set("progress", (currentIndex + 1) + "/" + pipeline.size());
            ONode stagesNode = root.getOrNew("stages");
            stages.forEach((k, v) -> {
                ONode s = stagesNode.asArray().addNew();
                s.set("agent", k).set("status", v.status).set("retries", v.retries);
            });
            return root.toJson();
        }
    }

    public SequentialProtocol_H(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "SEQUENTIAL"; }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        SequenceState state = (SequenceState) trace.getProtocolContext()
                .computeIfAbsent(KEY_SEQUENCE_STATE, k -> {
                    SequenceState s = new SequenceState();
                    s.init(trace.getConfig().getAgentMap().keySet());
                    return s;
                });

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n\n### ⛓️ 流水线执行状态 (Pipeline State)\n" : "\n\n### ⛓️ Pipeline State\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
    }

    @Override
    public boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        SequenceState state = (SequenceState) trace.getProtocolContext().get(KEY_SEQUENCE_STATE);
        if (state == null) return true;

        String next = state.getNextAgent();
        trace.setRoute(next);

        // 如果流水线走完了，直接结束，不需要 Supervisor 介入
        if (Agent.ID_END.equals(next)) {
            return false;
        }

        // 顺序协议通常是自动化流转，设为 false 表示协议直接指定路由
        return false;
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        SequenceState state = (SequenceState) trace.getProtocolContext().get(KEY_SEQUENCE_STATE);
        if (state == null) return;

        TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getStepCount() - 1);
        String content = lastStep.getContent();

        // 质量检查 (Quality Gate)
        boolean isSuccess = assessQuality(content);
        SequenceState.StageInfo info = state.stages.get(agent.name());

        if (isSuccess) {
            state.markCurrent("COMPLETED", content);
            state.next(); // 只有成功才推进索引
            LOG.info("Sequential Stage {} COMPLETED", agent.name());
        } else {
            if (info.retries < maxRetriesPerStage) {
                info.retries++;
                state.markCurrent("RETRYING", "Output quality low");
                LOG.warn("Sequential Stage {} RETRYING ({})", agent.name(), info.retries);
            } else {
                state.markCurrent("FAILED", "Max retries reached");
                state.next(); // 失败多次后强制跳过或结束（取决于配置）
                LOG.error("Sequential Stage {} FAILED", agent.name());
            }
        }
    }

    private boolean assessQuality(String content) {
        // 顺序协议最怕空输出或报错
        if (Utils.isEmpty(content)) return false;
        if (content.contains("ERROR") || content.contains("失败")) return false;
        return content.length() > 20; // 过于简短可能意味着 Agent 在敷衍
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_SEQUENCE_STATE);
        super.onTeamFinished(context, trace);
    }
}