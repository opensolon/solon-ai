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

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 团队协作管理决策任务
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamSupervisorTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(TeamSupervisorTask.class);
    private String name;
    private final ChatModel chatModel;
    private final List<String> agentNames;
    private int maxTotalIterations = 15;
    private TeamPromptProvider promptProvider = TeamPromptProviderEn.getInstance();

    public TeamSupervisorTask(ChatModel chatModel, String... agentNames) {
        this.chatModel = chatModel;
        this.agentNames = Arrays.asList(agentNames);
    }

    public TeamSupervisorTask promptProvider(TeamPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
        return this;
    }

    public TeamSupervisorTask maxTotalIterations(int maxTotalIterations) {
        this.maxTotalIterations = maxTotalIterations;
        return this;
    }

    public TeamSupervisorTask nameAs(String name) {
        this.name = name;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String prompt = context.getAs(Agent.KEY_PROMPT);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);

        // 1. 获取团队协作历史（从 Trace 获取）
        String teamHistory = (trace != null) ? trace.getFormattedHistory() : "No progress yet.";
        int iters = context.getOrDefault(Agent.KEY_ITERATIONS, 0);

        // 2. 熔断与循环检测
        if (iters >= maxTotalIterations || (trace != null && trace.isLooping())) {
            LOG.warn("MultiAgent team reached limit or detected loop. Forcing exit.");
            context.put(Agent.KEY_NEXT_AGENT, "end");
            return;
        }

        // 3. 构建决策请求
        String systemPrompt = promptProvider.getSystemPrompt(prompt, agentNames);
        String decision = chatModel.prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Collaboration Progress (Iteration " + iters + "):\n" + teamHistory)
        )).call().getResultContent().trim(); // 去除首尾空格

        // 4. 解析决策
        String nextAgent = "end";
        String decisionUpper = decision.toUpperCase();
        if (!decision.contains("FINISH")) {
            for (String name : agentNames) {
                // 使用包含匹配，并忽略大小写，防止标点符号干扰
                if (decisionUpper.contains(name.toUpperCase())) {
                    nextAgent = name;
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Supervisor decision: {} -> Next Agent: {}", decision, nextAgent);
        }

        context.put(Agent.KEY_NEXT_AGENT, nextAgent);
        context.put(Agent.KEY_ITERATIONS, iters + 1);
    }
}