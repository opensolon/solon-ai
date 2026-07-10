package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.AgentChunk;

/**
 *
 * @author noear 2026/7/10 created
 *
 */
public class TaskWrapChuck extends AbsAgentChunk {
    private final int taskIndex;
    private final String taskId;
    private final String taskAgentName;
    private final boolean isMultitask;
    private final AgentChunk realChunk;

    public TaskWrapChuck(int taskIndex, String taskId, String taskAgentName, boolean isMultitask, AgentChunk realChunk) {
        super(realChunk.getRunId(), realChunk.getAgentName(), realChunk.getSession(), realChunk.getMessage());
        this.taskIndex = taskIndex;
        this.taskId = taskId;
        this.taskAgentName = taskAgentName;
        this.isMultitask = isMultitask;
        this.realChunk = realChunk;
    }

    public int getTaskIndex() {
        return taskIndex;
    }


    public String getTaskId() {
        return taskId;
    }

    public String getTaskAgentName() {
        return taskAgentName;
    }

    public boolean isMultitask() {
        return isMultitask;
    }

    public AgentChunk getRealChunk() {
        return realChunk;
    }
}