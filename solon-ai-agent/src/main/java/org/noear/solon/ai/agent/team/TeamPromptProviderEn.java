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

import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * English Prompt Provider (Supports all TeamStrategy protocols) - Test Stable Version
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamPromptProviderEn implements TeamPromptProvider {
    private static final TeamPromptProviderEn INSTANCE = new TeamPromptProviderEn();

    public static TeamPromptProviderEn getInstance() {
        return INSTANCE;
    }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        TeamConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        sb.append("You are the Team Supervisor, responsible for coordinating the following agents to complete the task:\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- ").append(name).append(": ").append(agent.description()).append("\n");
        });

        sb.append("\nCurrent Task: ").append(trace.getPrompt().getUserContent()).append("\n");

        sb.append("\nCollaboration Protocol: ").append(config.getProtocol().name()).append("\n");
        config.getProtocol().injectInstruction(config, Locale.ENGLISH, sb);

        sb.append("\n### Output Specification\n");
        sb.append("1. Analyze current progress and decide the next action\n");
        sb.append("2. If the task is completed, output: ").append(config.getFinishMarker())
                .append(" followed by the complete and verbatim result from the last executive Agent. DO NOT add any extra summary or paraphrasing.\n");
        sb.append("3. Otherwise, output ONLY the name of the NEXT Agent to execute\n");

        // Simplified history analysis
        sb.append("\n### History Analysis\n");
        sb.append("You will receive collaboration history. If history contains enough information to answer the question, provide final answer directly.\n");

        // Simplified termination conditions
        sb.append("\n### Termination\n");
        sb.append("Output ").append(config.getFinishMarker()).append(" when task is done.\n");
        sb.append("Note: Don't terminate too early. Ensure necessary experts have a chance to contribute.");

        return sb.toString();
    }
}