package org.noear.solon.ai.agent.team;

import org.noear.solon.flow.Node;
import org.noear.solon.flow.NodeTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 团队协作轨迹（记录 MultiAgent 内部各智能体的协作流转）
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamTrace {
    private final List<TeamStep> steps = new ArrayList<>();
    private String finalAnswer;
    private NodeTrace lastNode;

    public NodeTrace getLastNode() {
        return lastNode;
    }

    public String getLastNodeId() {
        return (lastNode == null) ? null : lastNode.getId();
    }

    public void setLastNode(NodeTrace lastNode) {
        this.lastNode = lastNode;
    }

    public void setLastNode(Node lastNode) {
        this.lastNode = new NodeTrace(lastNode);
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
        if (n < 2) return false;

        // 如果同一个 Agent 连续 3 次被分配任务且内容高度相似（这里简化为连续 3 次分配给同一人）
        if (n >= 3) {
            String last1 = steps.get(n - 1).getAgentName();
            String last2 = steps.get(n - 2).getAgentName();
            String last3 = steps.get(n - 3).getAgentName();
            if (last1.equals(last2) && last2.equals(last3)) {
                return true; // 陷入原地踏步循环
            }
        }

        // 然后 A-B-A-B 逻辑
        if (n >= 4) {
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

        public String getAgentName() { return agentName; }
        public String getContent() { return content; }
        public long getDuration() { return duration; }
    }
}