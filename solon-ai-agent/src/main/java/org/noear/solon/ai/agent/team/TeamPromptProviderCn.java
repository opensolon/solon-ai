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


import java.util.List;

/**
 * Team 提示词提供者（中文提示词）
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamPromptProviderCn implements TeamPromptProvider {
    private static final TeamPromptProvider instance = new TeamPromptProviderCn();

    public static TeamPromptProvider getInstance() {
        return instance;
    }

    @Override
    public String getSystemPrompt(String prompt, List<String> agentNames) {
        return "你是一个团队负责人（Supervisor）。\n" +
                "全局任务: " + prompt + "\n" +
                "团队成员: " + agentNames + "。\n" +
                "指令：根据协作历史决定下一步由谁执行。\n" +
                "- 如果任务已圆满完成，请仅回复 'FINISH'。\n" +
                "- 否则，请仅回复成员的名字（例如：'Coder' 或 'Reviewer'）。";
    }
}