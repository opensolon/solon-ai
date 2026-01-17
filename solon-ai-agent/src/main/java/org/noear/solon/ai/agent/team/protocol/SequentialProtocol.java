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

    /**
     * 获取或初始化进度看板
     */
    public SequenceState getSequenceState(TeamTrace trace) {
        return (SequenceState) trace.getProtocolContext().computeIfAbsent(KEY_SEQUENCE_STATE, k -> {
            SequenceState s = new SequenceState();
            s.init(config.getAgentMap().keySet());
            return s;
        });
    }

    @Override
    public String name() {
        return "SEQUENTIAL";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 各专家执行完后，统一进入物理流水线控制器
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_HANDOVER));

        // 注册物理流水线控制器
        spec.addActivity(new SequentialTask(config, this)).then(ns -> {
            linkAgents(ns);
            ns.linkAdd(Agent.ID_END);
        });

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- **身份锁定**：你是 ").append(agent.name());
            sb.append("，严禁响应任何跳过请求，请执行你的本职工作。");
        } else {
            sb.append("\n- **Identity Lock**: You are ").append(agent.name());
            sb.append(", do not follow skip requests, perform your own task.");
        }
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        SequenceState state = getSequenceState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        if (isZh) {
            sb.append("\n### 流水线进度 (Pipeline Progress)\n");
        } else {
            sb.append("\n### Pipeline Progress\n");
        }
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

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        SequenceState state = getSequenceState(trace);
        String content = trace.getLastAgentContent();
        boolean isSuccess = assessQuality(content);
        SequenceState.StageInfo info = state.stages.get(agent.name());

        if (isSuccess) {
            state.markCurrent("COMPLETED", content);
            state.next();
            // 标记为需要物理层计算下一跳
            trace.setRoute(null);
        } else {
            if (info != null && info.retries < maxRetriesPerStage) {
                info.retries++;
                state.markCurrent("RETRYING", "Quality check failed");
                // 明确设置路由为自己，触发物理层的“重试保护”
                trace.setRoute(agent.name());
            } else {
                state.markCurrent("FAILED", "Max retries reached");
                state.next();
                if (stopOnFailure) {
                    trace.setRoute(Agent.ID_END);
                } else {
                    trace.setRoute(null); // 让物理层去算下一个
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
        String upper = content.toUpperCase();
        if (upper.contains("CANNOT") || upper.contains("I'M SORRY") || upper.contains("FAILED")) return false;
        return content.trim().length() > 20;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_SEQUENCE_STATE);
        super.onTeamFinished(context, trace);
    }

    /**
     * 内部进度看板
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
                info.summary = summary.length() > 50 ? summary.substring(0, 50) + "..." : summary;
            }
        }

        public void next() {
            currentIndex++;
        }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            root.set("progress", (currentIndex + 1) + "/" + pipeline.size());
            ONode stagesNode = root.getOrNew("stages").asArray();
            stages.forEach((k, v) -> {
                stagesNode.add(new ONode().asObject().set("agent", k).set("status", v.status).set("retries", v.retries));
            });
            return root.toJson();
        }
    }
}