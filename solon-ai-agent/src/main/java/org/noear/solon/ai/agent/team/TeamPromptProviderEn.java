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
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Team 提示词提供者（英文提示词）
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamPromptProviderEn implements TeamPromptProvider {
    private static final TeamPromptProvider instance = new TeamPromptProviderEn();

    public static TeamPromptProvider getInstance() {
        return instance;
    }

    @Override
    public String getSystemPrompt(TeamConfig config, Prompt prompt) {
        String specialists = config.getAgentMap().values().stream()
                .map(a -> String.format("- %s: %s", a.name(), a.description()))
                .collect(Collectors.joining("\n"));

        return "You are a team supervisor. \n" +
                "Global Task: " + prompt.getUserContent() + "\n" +
                "Team Members and their Responsibilities:\n" + specialists + "\n" +
                "Instruction: Review the collaboration history and decide who should act next. \n" +
                "- If the task is finished, respond ONLY with '" + config.getFinishMarker() + "'. \n" +
                "- Otherwise, respond ONLY with the specialist's name.";
    }
}