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
public class SequentialProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(SequentialProtocol.class);
    private static final String KEY_SEQUENCE_STATE = "sequence_state_obj";
    private int maxRetriesPerStage = 1;
    private boolean stopOnFailure = false;

    /**
     * 内部流水线进度看板
     */
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
                // 仅保留摘要摘要，避免状态看板过大
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

    public SequentialProtocol(TeamAgentConfig config) {
        super(config);
    }

    public SequentialProtocol stopOnFailure(boolean stopOnFailure) {
        this.stopOnFailure = stopOnFailure;
        return this;
    }

    public SequentialProtocol maxRetriesPerStage(int maxRetriesPerStage) {
        this.maxRetriesPerStage = maxRetriesPerStage;
        return this;
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
        sb.append(isZh ? "\n### 流水线进度 (Pipeline Progress)\n" : "\n### Pipeline Progress\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- **顺序执行**：按流水线顺序执行，严禁乱序。");
            sb.append("\n- **跳过策略**：若下个成员不支持当前输入模态（如图像），请执行跳过。");
        } else {
            sb.append("\n- **Sequential**: Follow the pipeline order strictly.");
            sb.append("\n- **Skip Strategy**: Skip the next member if it doesn't support current modality.");
        }
    }

    /**
     * 路由决策逻辑：在此环节实现模态防御
     */
    @Override
    public boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        SequenceState state = (SequenceState) trace.getProtocolContext().get(KEY_SEQUENCE_STATE);
        if (state == null) return true;

        String next = state.getNextAgent();

        // 循环检查：跳过模态不匹配的 Agent
        while (!Agent.ID_END.equals(next)) {
            Agent nextAgent = config.getAgentMap().get(next);
            boolean hasImage = detectMediaPresence(trace);

            if (hasImage && nextAgent.profile() != null) {
                boolean supportImage = nextAgent.profile().getInputModes().contains("image");

                if (!supportImage) {
                    LOG.warn("Sequential Protocol: Skipping Agent [{}] - Incompatible with image data", next);
                    state.markCurrent("SKIPPED", "Incompatible modality");
                    state.next();
                    next = state.getNextAgent();
                    continue;
                }
            }
            break;
        }

        trace.setRoute(next);
        return false; // 由协议直接接管路由，不再交由 LLM 决定
    }

    private boolean detectMediaPresence(TeamTrace trace) {
        String content = trace.getLastAgentContent();
        if (content == null) return false;
        // 匹配标准图片 Markdown 或 DataURI
        return content.contains("![image]") || content.matches("(?s).*\\[.*?\\]\\(data:image/.*\\).*");
    }

    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String rawContent) {
        if (!assessQuality(rawContent)) {
            return "[System: Quality Check Failed - Waiting for Retry]";
        }
        return rawContent;
    }

    /**
     * 阶段结束回调：在此处理重试与熔断逻辑
     */
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
            if (LOG.isDebugEnabled()) {
                LOG.debug("Sequential Protocol: Stage [{}] finished successfully", agent.name());
            }
        } else {
            if (info != null && info.retries < maxRetriesPerStage) {
                info.retries++;
                state.markCurrent("RETRYING", "Quality check failed");
                LOG.warn("Sequential Protocol: Stage [{}] failed quality check. Retrying... ({}/{})",
                        agent.name(), info.retries, maxRetriesPerStage);
            } else {
                state.markCurrent("FAILED", "Max retries reached");
                if (stopOnFailure) {
                    trace.setRoute(Agent.ID_END);
                    LOG.error("Sequential Protocol: Critical Failure at [{}]. Halting team.", agent.name());
                } else {
                    state.next();
                    LOG.warn("Sequential Protocol: Stage [{}] failed. Skipping to next.", agent.name());
                }
            }
        }
        super.onAgentEnd(trace, agent);
    }

    /**
     * 质量评估逻辑：识别常见的拒绝回复或过短内容
     */
    private boolean assessQuality(String content) {
        if (Utils.isEmpty(content)) return false;
        String upper = content.toUpperCase();
        if (upper.contains("CANNOT") || upper.contains("I'M SORRY") || upper.contains("FAILED")) return false;
        return content.trim().length() > 20;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_SEQUENCE_STATE);
        super.onTeamFinished(context, trace);
    }
}