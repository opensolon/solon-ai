package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;

public class AgentRouterTask implements TaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRouterTask.class);
    private final ChatModel chatModel;
    private final List<String> agentNames;
    private final int maxTotalIterations = 15; // 团队协作上限

    public AgentRouterTask(ChatModel chatModel, String... agentNames) {
        this.chatModel = chatModel;
        this.agentNames = Arrays.asList(agentNames);
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String prompt = context.getAs(Agent.KEY_PROMPT);
        String history = context.getOrDefault(Agent.KEY_HISTORY, "No progress yet.");
        int iters = context.getOrDefault(Agent.KEY_ITERATIONS, 0);

        // 1. 熔断检查
        if (iters >= maxTotalIterations) {
            LOG.warn("MultiAgent team reached max iterations. Forcing exit.");
            context.put(Agent.KEY_NEXT_AGENT, "end");
            return;
        }

        // 2. 构建 Supervisor 提示词
        String systemPrompt = "You are a team supervisor. Task: " + prompt + "\n" +
                "Available specialists: " + agentNames + ".\n" +
                "Instruction: Review the history and decide who works next. \n" +
                "If the task is complete, return 'FINISH'. \n" +
                "Respond ONLY with the name or 'FINISH'.";

        // 3. 获取决策并严格解析
        String decision = chatModel.prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Current Team History (Iter " + iters + "):\n" + history)
        )).call().getResultContent().toUpperCase();

        String nextAgent = "end";
        if (!decision.contains("FINISH")) {
            for (String name : agentNames) {
                if (decision.contains(name.toUpperCase())) {
                    nextAgent = name;
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Supervisor decision: {} -> Next: {}", decision, nextAgent);
        }

        context.put(Agent.KEY_NEXT_AGENT, nextAgent);
    }
}