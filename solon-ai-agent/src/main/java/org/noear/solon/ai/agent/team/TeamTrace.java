/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team;

import org.noear.solon.flow.NodeTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 团队协作轨迹（记录团队内部各智能体的协作流转）
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamTrace {
    private final List<TeamStep> steps = new ArrayList<>();

    private volatile String route;
    private AtomicInteger iterations;

    private String finalAnswer;
    private NodeTrace lastNode;

    public TeamTrace() {
        this.iterations = new AtomicInteger(0);
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public int iterationsCount() {
        return iterations.get();
    }

    public void resetIterations() {
        iterations.set(0);
    }

    public int nextIterations() {
        return iterations.incrementAndGet();
    }

    public NodeTrace getLastNode() {
        return lastNode;
    }

    public String getLastNodeId() {
        return (lastNode == null) ? null : lastNode.getId();
    }

    public void setLastNode(NodeTrace lastNode) {
        this.lastNode = lastNode;
    }

    /**
     * 获取步骤总数
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * 添加协作步骤
     */
    public void addStep(String agentName, String content, long duration) {
        steps.add(new TeamStep(agentName, content, duration));
    }

    /**
     * 获取格式化的协作历史（用于提供给 Supervisor 决策）
     */
    public String getFormattedHistory() {
        if (steps.isEmpty()) {
            return "No progress yet.";
        }
        return steps.stream()
                .map(step -> String.format("[%s]: %s", step.getAgentName(), step.getContent()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 检查是否陷入了简单的双人循环（例如 A -> B -> A -> B）
     */
    public boolean isLooping() {
        int n = steps.size();
        if (n < 4) return false; // 放宽到至少4步才可能形成循环

        String lastAgent = steps.get(n - 1).getAgentName();
        String lastContent = steps.get(n - 1).getContent();

        // 检查是否有同一个 Agent 连续产出完全相同的内容
        for (int i = 0; i < n - 1; i++) {
            TeamStep prev = steps.get(i);
            if (prev.getAgentName().equals(lastAgent) && prev.getContent().equals(lastContent)) {
                // 如果同一个 Agent 输出了跟之前一模一样的内容，则判定为死循环
                return true;
            }
        }

        // A-B-A-B 模式检测，放宽条件
        if (n >= 8) { // 需要更多步才检测 A-B-A-B
            return steps.get(n - 1).getAgentName().equals(steps.get(n - 3).getAgentName()) &&
                    steps.get(n - 2).getAgentName().equals(steps.get(n - 4).getAgentName());
        }

        return false;
    }

    public List<TeamStep> getSteps() {
        return steps;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    /**
     * 单个协作步骤实体
     */
    public static class TeamStep {
        private final String agentName;
        private final String content;
        private final long duration;

        public TeamStep(String agentName, String content, long duration) {
            this.agentName = agentName;
            this.content = content;
            this.duration = duration;
        }

        public String getAgentName() {
            return agentName;
        }

        public String getContent() {
            return content;
        }

        public long getDuration() {
            return duration;
        }
    }
}