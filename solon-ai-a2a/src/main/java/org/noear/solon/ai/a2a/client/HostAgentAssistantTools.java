package org.noear.solon.ai.a2a.client;

import org.noear.solon.ai.a2a.model.Message;
import org.noear.solon.ai.a2a.model.TaskSendParams;
import org.noear.solon.ai.a2a.model.TextPart;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @author haiTao.Wang on 2025/8/21.
 */
class HostAgentAssistantTools {
    private HostAgent hostAgent;

    public HostAgentAssistantTools(HostAgent hostAgent) {
        this.hostAgent = hostAgent;
    }

    @ToolMapping(description = "List the available remote agents you can use to delegate the task.")
    public List<Map<String, String>> list_remote_agents() {

        System.err.println("list_remote_agents:" + hostAgent.getAgentInfo());
        return hostAgent.getAgentInfo();
    }

    @ToolMapping(description = "发送一个任务，要么以流式传输（如果支持的话），要么以非流式传输。这将向名为 agent_name 的远程代理发送一条消息。")
    public String send_message(@Param(description = "要将任务发送给的代理的名称") String agentName,
                               @Param(description = "需要发送给执行该任务的代理的信息") String message) throws Exception {

        System.err.println("send_message:" + agentName + ":" + message);

        TaskSendParams taskSendParams = new TaskSendParams()
                .setId("1")
                .setSessionId("1")
                .setMessage(new Message("1", "user", Arrays.asList(new TextPart(message))));

        return hostAgent.getA2AClientMap().get(agentName).sendTask(taskSendParams).getResult().toString();
    }
}