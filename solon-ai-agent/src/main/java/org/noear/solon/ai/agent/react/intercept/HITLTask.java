package org.noear.solon.ai.agent.react.intercept;

import java.io.Serializable;
import java.util.Map;

/**
 * HITL 挂起任务实体
 */
public class HITLTask implements Serializable {
    private final String toolName;
    private final Map<String, Object> args;

    public HITLTask(String toolName, Map<String, Object> args) {
        this.toolName = toolName;
        this.args = args;
    }

    /**
     * 获取拟调用的工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取拟调用的参数快照
     */
    public Map<String, Object> getArgs() {
        return args;
    }

    @Override
    public String toString() {
        return "HITLTask{" +
                "toolName='" + toolName + '\'' +
                ", args=" + args +
                '}';
    }
}