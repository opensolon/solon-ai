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
package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;

/**
 * 智能体路由任务
 *
 * @author noear
 * @since 3.8.1
 * */
@Preview("3.8")
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
        String teamHistory = context.getOrDefault(Agent.KEY_HISTORY, "No progress yet.");
        int iters = context.getOrDefault(Agent.KEY_ITERATIONS, 0);

        if (iters >= maxTotalIterations) {
            LOG.warn("MultiAgent team reached max iterations. Forcing exit.");
            context.put(Agent.KEY_NEXT_AGENT, "end");
            return;
        }

        // 2. 构建 Supervisor 提示词
        String systemPrompt = "You are a team supervisor. \n" +
                "Global Task: " + prompt + "\n" +
                "Specialists: " + agentNames + ".\n" +
                "Instruction: Review the collaboration history and decide who should act next. \n" +
                "- If the task is finished, respond ONLY with 'FINISH'. \n" +
                "- Otherwise, respond ONLY with the specialist's name.";

        // 3. 获取决策
        String decision = chatModel.prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Collaboration Progress (Iter " + iters + "):\n" + teamHistory)
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