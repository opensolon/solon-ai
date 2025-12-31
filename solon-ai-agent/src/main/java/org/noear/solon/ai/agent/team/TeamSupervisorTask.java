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
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 团队协作管理任务
 *
 * @author noear
 * @since 3.8.1
 */
 class TeamSupervisorTask implements TaskComponent {
    static final Logger LOG = LoggerFactory.getLogger(TeamSupervisorTask.class);
    private final TeamConfig config;
    private final String teamName;

    public TeamSupervisorTask(TeamConfig config) {
        this.teamName = config.getName();
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Prompt prompt = context.getAs(Agent.KEY_PROMPT);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);

        // 1. 获取团队协作历史（从 Trace 获取）
        String teamHistory = trace.getFormattedHistory();
        int iters = trace.iterationsCount();

        // 2. 熔断与循环检测
        if (iters >= config.getMaxTotalIterations() || (trace != null && trace.isLooping())) {
            String reason = iters >= config.getMaxTotalIterations() ? "Maximum iterations reached" : "Loop detected";
            LOG.warn("Team Agent [{}] forced exit: {}", teamName, reason);
            if (trace != null) {
                trace.addStep("system", "Execution halted: " + reason, 0);
            }
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 3. 构建决策请求
        String systemPrompt = config.getSystemPrompt(prompt);
        String decision = config.getChatModel().prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Collaboration Progress (Iteration " + iters + "):\n" + teamHistory)
        )).call().getResultContent().trim(); // 去除首尾空格

        // 4. 解析决策
        String nextAgent = Agent.ID_END;
        String cleanDecision = " " + decision.toUpperCase() + " "; //不要去掉符号（会失真）

        if (!cleanDecision.contains(config.getFinishMarker())) {
            List<String> sortedNames = config.getAgentMap().keySet().stream()
                    .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                    .collect(Collectors.toList());

            for (String name : sortedNames) {
                if (cleanDecision.contains(" " + name.toUpperCase() + " ")) {
                    nextAgent = name;
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Supervisor decision: {} -> Next Agent: {}", decision, nextAgent);
        }

        trace.setRoute(nextAgent);
        trace.nextIterations();
    }
}
