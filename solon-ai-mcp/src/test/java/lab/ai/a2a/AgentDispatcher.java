package lab.ai.a2a;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 智能体调度器
 *
 * @author noear
 * @since 3.5
 */
public class AgentDispatcher implements ToolProvider , AutoCloseable {
    static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);

    private final Map<String, FunctionTool> agentLib = new HashMap<>();
    private final List<Map<String, String>> agentInfo = new ArrayList();
    private final List<ToolProvider> agentProviders = new ArrayList<>();

    private final List<FunctionTool> dispatcherTools = new ArrayList<>();

    public AgentDispatcher() {
        dispatcherTools.add(new FunctionToolDesc("list_agents")
                .description("列出可用于委派任务的可用代理。")
                .doHandle(args -> {
                    return ONode.serialize(agentInfo);
                }));


        dispatcherTools.add(new FunctionToolDesc("send_message")
                .description("发送一个任务，要么以流式传输（如果支持的话），要么以非流式传输。这将向名为 agent_name 的代理发送一条消息。")
                .stringParamAdd("agentName", "要将任务发送给的代理的名称")
                .stringParamAdd("message", "需要发送给执行该任务的代理的信息")
                .doHandle(args -> {
                    String agentName = (String) args.get("agentName");
                    String message = (String) args.get("message");

                    FunctionTool agent = agentLib.get(agentName);

                    log.debug("agent-request: {agentName: {}, message: {}}", agentName, message);

                    String result = agent.handle(Utils.asMap("message", message));

                    log.debug("agent-response: {agentName: {}, message: {}, result: {}}", agentName, message, result);

                    return result;
                }));
    }

    @Override
    public Collection<FunctionTool> getTools() {
        return dispatcherTools;
    }

    /**
     * 注册智能体
     */
    public void register(FunctionTool agent) {
        agentLib.put(agent.name(), agent);
        agentInfo.add(Utils.asMap("agentName", agent.name(), "description", agent.description()));
    }

    /**
     * 注册智能体
     */
    public void register(ToolProvider agentProvider) {
        agentProviders.add(agentProvider);

        for (FunctionTool agent : agentProvider.getTools()) {
            register(agent);
        }
    }

    public SystemMessage systemMessage() {
        StringBuilder systemPrompt = new StringBuilder(
                "您是一位擅长分配任务的专家，负责将用户请求分解为子代理可以执行的任务。能够将用户请求分配给合适的代理。\n" +
                        "\n" +
                        "发现：\n" +
                        "- 你可以使用工具 `list_agents` 列出可用于分配任务的代理。\n" +
                        "\n" +
                        "执行：\n" +
                        "- 对于可操作的请求，您可以使用工具 `send_message` 与代理交互以获取结果。\n" +
                        " \n" +
                        "请在回复用户时包含代理的名称。\n" +
                        "\n" +
                        "请依靠工具来处理请求，不要编造回复。如果您不确定，请向用户询问更多细节。\n" +
                        "主要关注对话的最新部分。将子任务的答案代入回答每个子任务的结果。\n" +
                        "\n" +
                        "代理:\n");

        for (Map item : agentInfo) {
            systemPrompt.append(item).append("\n");
        }

        return ChatMessage.ofSystem(systemPrompt.toString());
    }

    @Override
    public void close() throws Exception {
        for (ToolProvider agentProvider : agentProviders) {
            if (agentProvider instanceof AutoCloseable) {
                AutoCloseable tmp = (AutoCloseable) agentProvider;
                tmp.close();
            }
        }
    }
}
