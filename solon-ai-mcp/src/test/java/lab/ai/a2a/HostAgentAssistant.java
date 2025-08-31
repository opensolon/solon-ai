package lab.ai.a2a;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author noear 2025/8/31 created
 *
 */
public class HostAgentAssistant {
    static final Logger log = LoggerFactory.getLogger(HostAgentAssistant.class);

    private final Map<String, FunctionTool> agentLib = new HashMap<>();
    private final List<Map<String, String>> agentInfo = new ArrayList();

    /**
     * 注册智能体
     *
     */
    public void register(FunctionTool agent) {
        agentLib.put(agent.name(), agent);
        agentInfo.add(Utils.asMap("agentName", agent.name(), "description", agent.description()));
    }


    @ToolMapping(description = "列出可用于委派任务的可用智能体。")
    public List<Map<String, String>> list_agents() {
        if (log.isDebugEnabled()) {
            log.debug("list_remote_agents:" + agentInfo);
        }

        return agentInfo;
    }

    @ToolMapping(description = "发送一个任务，要么以流式传输（如果支持的话），要么以非流式传输。这将向名为 agent_name 的远程代理发送一条消息。")
    public String send_message(@Param(description = "要将任务发送给的代理的名称") String agentName,
                               @Param(description = "需要发送给执行该任务的代理的信息") String message) throws Throwable {

        if (log.isDebugEnabled()) {
            log.debug("send_message:" + agentName + ":" + message);
        }

        FunctionTool agent = agentLib.get(agentName);
        return agent.handle(Utils.asMap("message", message));
    }
}
