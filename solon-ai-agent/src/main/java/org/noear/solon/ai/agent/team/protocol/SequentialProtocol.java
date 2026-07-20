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
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

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
    public final static String ID_ROUTING = "routing";

    private static final String KEY_SEQUENCE_STATE = "sequence_state_obj";
    private static final int SUMMARY_MAX_LENGTH = 50;
    private int maxRetriesPerStage = 1;

    public enum StageStatus {
        PENDING, COMPLETED, RETRYING, FAILED, SKIPPED
    }

    public static class StageInfo {
        public StageStatus status = StageStatus.PENDING;
        public int retries = 0;
        public String summary;
    }

    public void setMaxRetriesPerStage(int maxRetriesPerStage) {
        this.maxRetriesPerStage = maxRetriesPerStage;
    }

    public static class SequenceState {
        private final List<String> pipeline = new ArrayList<>();
        private int currentIndex = 0;
        private final Map<String, StageInfo> stages = new LinkedHashMap<>();

        public void init(TeamTrace trace) {
            if (this.pipeline.size() > 0) return;
            if (trace == null || trace.getConfig() == null) return;

            Collection<String> agentNames = trace.getConfig().getAgentMap().keySet();
            this.pipeline.addAll(agentNames);
            agentNames.forEach(name -> stages.put(name, new StageInfo()));

            // 按 pipeline 物理位置恢复索引：找到第一个尚未终态的阶段，
            // 避免用 finished.size() 在 SKIPPED/乱序参与时错位
            if (trace.getRecords() != null) {
                Map<String, StageStatus> recovered = new LinkedHashMap<>();
                for (TeamTrace.TeamRecord r : trace.getRecords()) {
                    if (r == null || !r.isAgent()) continue;
                    String name = r.getSource();
                    if (!pipeline.contains(name)) continue;

                    String content = String.valueOf(r.getContent());
                    if (content.contains("Incompatible modality") || content.contains("SKIPPED")) {
                        recovered.put(name, StageStatus.SKIPPED);
                    } else if (content.contains("Quality check failed") || content.contains("FAILED")) {
                        recovered.put(name, StageStatus.FAILED);
                    } else {
                        recovered.put(name, StageStatus.COMPLETED);
                    }
                }

                recovered.forEach((name, status) -> {
                    StageInfo info = stages.get(name);
                    if (info != null) {
                        info.status = status;
                    }
                });

                this.currentIndex = 0;
                while (this.currentIndex < pipeline.size()) {
                    StageInfo info = stages.get(pipeline.get(this.currentIndex));
                    if (info == null) {
                        break;
                    }
                    if (info.status == StageStatus.COMPLETED
                            || info.status == StageStatus.FAILED
                            || info.status == StageStatus.SKIPPED) {
                        this.currentIndex++;
                    } else {
                        break;
                    }
                }
            }
        }

        public String getNextAgent() {
            return currentIndex < pipeline.size() ? pipeline.get(currentIndex) : Agent.ID_END;
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public List<String> getPipeline() {
            return Collections.unmodifiableList(pipeline);
        }

        public Map<String, StageInfo> getStages() {
            return Collections.unmodifiableMap(stages);
        }

        public void markCurrent(StageStatus status, String summary) {
            if (currentIndex < pipeline.size()) {
                StageInfo info = stages.get(pipeline.get(currentIndex));
                if (info != null) {
                    info.status = status;
                    if (Utils.isNotEmpty(summary)) {
                        info.summary = summary.length() > SUMMARY_MAX_LENGTH
                                ? summary.substring(0, SUMMARY_MAX_LENGTH) + "..."
                                : summary;
                    }
                }
            }
        }

        /**
         * 按 agent 名标记阶段（用于 onAgentEnd 时 currentIndex 与 agent 对齐校验）
         */
        public void markAgent(String agentName, StageStatus status, String summary) {
            StageInfo info = stages.get(agentName);
            if (info == null) return;
            info.status = status;
            if (Utils.isNotEmpty(summary)) {
                info.summary = summary.length() > SUMMARY_MAX_LENGTH
                        ? summary.substring(0, SUMMARY_MAX_LENGTH) + "..."
                        : summary;
            }
        }

        public void next() { currentIndex++; }

        /**
         * 将 currentIndex 推进到指定 agent 的下一位置（若 agent 不在 pipeline 则 next()）
         */
        public void advancePast(String agentName) {
            int idx = pipeline.indexOf(agentName);
            if (idx >= 0) {
                currentIndex = Math.min(idx + 1, pipeline.size());
            } else {
                next();
            }
        }

        /** 将 currentIndex 回退到指定 agent（用于阶段重试） */
        public void rewindTo(String agentName) {
            int idx = pipeline.indexOf(agentName);
            if (idx >= 0) {
                currentIndex = idx;
            }
        }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            int progress = Math.min(currentIndex + 1, pipeline.size());
            root.set("progress", progress + "/" + pipeline.size());
            ONode stagesNode = root.getOrNew("stages").asArray();
            stages.forEach((k, v) -> {
                stagesNode.add(new ONode().asObject()
                        .set("agent", k)
                        .set("status", v.status.name())
                        .set("retries", v.retries));
            });
            return root.toJson();
        }
    }

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
        spec.addStart(Agent.ID_START).linkAdd(ID_ROUTING);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(ID_ROUTING));

        spec.addActivity(new SequentialRoutingTask(config, this)).then(ns -> {
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
        sb.append("```json\n").append(ONode.serialize(state)).append("\n```\n");
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        SequenceState state = getSequenceState(trace);
        String content = trace.getLastAgentContent();
        boolean isPending = trace.getSession() != null && trace.getSession().isPending();
        StageInfo info = state.getStages().get(agent.name());

        // HITL / 人工挂起：冻结当前阶段，不消耗质量重试额度，也不推进流水线
        if (isPending) {
            state.markAgent(agent.name(), StageStatus.RETRYING, "Pending human intervention");
            state.rewindTo(agent.name());
            trace.setRoute(agent.name());
            super.onAgentEnd(trace, agent);
            return;
        }

        boolean isSuccess = assessQuality(content);

        if (isSuccess) {
            state.markAgent(agent.name(), StageStatus.COMPLETED, content);
            state.advancePast(agent.name());
            trace.setRoute(ID_ROUTING);
        } else {
            if (info != null && info.retries < maxRetriesPerStage) {
                info.retries++;
                state.markAgent(agent.name(), StageStatus.RETRYING, "Quality check failed");
                // 回退 currentIndex 到该 agent，确保重试时 getNextAgent 正确
                state.rewindTo(agent.name());
                trace.setRoute(agent.name());
            } else {
                state.markAgent(agent.name(), StageStatus.FAILED, "Quality check failed");
                state.advancePast(agent.name());
                trace.setRoute(ID_ROUTING);
            }
        }
        super.onAgentEnd(trace, agent);
    }

    /**
     * 检测上下文是否含多模态输入：优先看 originalPrompt 的 UserMessage，
     * 再回退扫描最近专家产出中的 markdown 图片标记。
     */
    public boolean detectMultiModalPresence(TeamTrace trace) {
        if (trace == null) return false;

        Prompt original = trace.getOriginalPrompt();
        if (original != null && original.getMessages() != null) {
            boolean fromPrompt = original.getMessages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .anyMatch(UserMessage::isMultiModal);
            if (fromPrompt) {
                return true;
            }
        }

        String content = trace.getLastAgentContent();
        if (content == null) return false;
        return content.contains("![image]") || content.matches("(?s).*\\[.*?\\]\\(data:image/.*\\).*");
    }

    /**
     * 专家是否支持图像模态（profile / inputModes 空安全）
     */
    public boolean supportsImage(Agent agent) {
        if (agent == null || agent.profile() == null) {
            return false;
        }
        List<String> modes = agent.profile().getInputModes();
        if (modes == null || modes.isEmpty()) {
            return false;
        }
        return modes.stream().anyMatch(m -> m != null && m.equalsIgnoreCase("image"));
    }

    private boolean assessQuality(String content) {
        if (Utils.isEmpty(content)) return false;
        // 基础质量检查：内容长度超过2个字符视为有效
        return content.trim().length() > 2;
    }

    /**
     * Sequential 以 pipeline 推进为准：至少走过最后一阶段（含 SKIPPED/FAILED）才允许逻辑完结
     */
    @Override
    protected boolean isLogicFinished(TeamTrace trace) {
        SequenceState state = getSequenceState(trace);
        return state.getCurrentIndex() >= state.getPipeline().size() && !state.getPipeline().isEmpty();
    }
}