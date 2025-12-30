package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.Arrays;
import java.util.List;

/**
 * 路由节点：负责解析当前状态并决定跳转到哪个 Agent
 */
public class AgentRouterTask implements TaskComponent {
    private final ChatModel chatModel;
    private final List<String> agentNames;

    public AgentRouterTask(ChatModel chatModel, String... agentNames) {
        this.chatModel = chatModel;
        this.agentNames = Arrays.asList(agentNames);
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String prompt = context.getAs("prompt");
        String history = context.getAs("history"); // 假设累积的对话简报

        // 构建决策提示词
        String systemPrompt = "You are a supervisor. Based on the user request, " +
                "decide which specialist should work next: " + agentNames +
                ". If the task is finished, return 'FINISH'.";

        String decision = chatModel.prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Context: " + history + "\nUser: " + prompt)
        )).call().getResultContent();

        // 将决策结果存入上下文，供 Graph 的 link 使用
        context.put("next_agent", decision.trim());
    }
}