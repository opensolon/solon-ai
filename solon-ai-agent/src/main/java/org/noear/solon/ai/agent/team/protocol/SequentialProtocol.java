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
    private boolean stopOnFailure = false; // 默认失败不停止

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
        sb.append(isZh ? "\n### 流水线执行状态 (Pipeline State)\n" : "\n### Pipeline State\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- 顺序执行模式：请按成员名录顺序调度。");
            sb.append("\n- **模态检查**：若当前任务包含非文本数据，请确保下一位成员支持该输入模态。");
            sb.append("\n- 注意：若发现违背了下一位成员的“行为约束”或“模态不支持”，建议跳过该成员。");
        } else {
            sb.append("\n- Sequential Mode: Follow the predefined order.");
            sb.append("\n- **Modality Check**: Ensure the next member supports the input modality if data is non-text.");
            sb.append("\n- Note: Skip members if they don't support the modality or violate constraints.");
        }
    }

    @Override
    public boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        SequenceState state = (SequenceState) trace.getProtocolContext().get(KEY_SEQUENCE_STATE);
        if (state == null) return true;

        String next = state.getNextAgent();

        // --- 模态适配防御逻辑 ---
        while (!Agent.ID_END.equals(next)) {
            Agent nextAgent = config.getAgentMap().get(next);

            // 判定当前上下文是否包含多模态数据
            // 逻辑：如果最后一次输出包含图片，或者初始消息包含图片
            boolean hasImage = detectMediaPresence(trace);

            if (hasImage && nextAgent.profile() != null) {
                // 检查目标 Agent 是否明确声明支持 'image'
                boolean supportImage = nextAgent.profile().getInputModes().contains("image");

                if (!supportImage) {
                    LOG.warn("Sequential: Auto-skipping [{}] because it doesn't support 'image' mode", next);
                    state.markCurrent("SKIPPED", "Incompatible modality (no image support)");
                    state.next();
                    next = state.getNextAgent();
                    continue;
                }
            }
            break;
        }
        // --- 逻辑结束 ---

        trace.setRoute(next);
        return false;
    }

    /**
     * 探测当前协作链路中是否存在多模态媒体数据
     */
    private boolean detectMediaPresence(TeamTrace trace) {
        String content = trace.getLastAgentContent();
        if (content == null) return false;

        // 增加对标准 Markdown 图片语法的支持判断
        // 同时也保留你的自定义占位符判断
        return content.contains("![image]") || content.matches("(?s).*\\[.*?\\]\\(data:image/.*\\).*");
    }

    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String rawContent) {
        if (!assessQuality(rawContent)) {
            // 如果质量不达标，返回一个标志位或空，防止垃圾内容污染后续步骤
            return "[System: Stage execution failed quality check, awaiting retry]";
        }
        return rawContent;
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
                // --- 重试逻辑 ---
                info.retries++;
                state.markCurrent("RETRYING", "Quality check failed, retrying...");
                LOG.warn("Sequential Stage [{}] RETRYING ({}/{})", agent.name(), info.retries, maxRetriesPerStage);
            } else {
                // --- 最终失败逻辑（在这里插入熔断判断） ---
                state.markCurrent("FAILED", "Max retries reached");

                if (stopOnFailure) {
                    // 强依赖模式：立即路由到结束节点
                    trace.setRoute(Agent.ID_END);
                    LOG.error("Sequential Stage [{}] FAILED. Stopping team task (stopOnFailure=true).", agent.name());
                } else {
                    // 弱依赖模式：跳过当前节点，继续下一个
                    state.next();
                    LOG.error("Sequential Stage [{}] FAILED. Moving to next stage (stopOnFailure=false).", agent.name());
                }
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