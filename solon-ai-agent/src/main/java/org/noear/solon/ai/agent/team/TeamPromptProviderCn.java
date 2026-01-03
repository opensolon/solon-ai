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
 * 中文提示词提供者（支持全策略协议引导）- 测试稳定版
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class TeamPromptProviderCn implements TeamPromptProvider {
    private static final TeamPromptProviderCn INSTANCE = new TeamPromptProviderCn();
    public static TeamPromptProviderCn getInstance() { return INSTANCE; }

    @Override
    public String getSystemPrompt(TeamTrace trace) {
        TeamConfig config = trace.getConfig();
        StringBuilder sb = new StringBuilder();

        sb.append("你是一个团队协作主管 (Supervisor)，负责协调以下 Agent 成员完成任务：\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- ").append(name).append(": ").append(agent.description()).append("\n");
        });

        sb.append("\n当前任务：").append(trace.getPrompt().getUserContent()).append("\n");

        sb.append("\n协作协议：").append(config.getProtocol().name()).append("\n");
        config.getProtocol().injectInstruction(config, Locale.CHINESE, sb);

        sb.append("\n### 输出规范\n");
        sb.append("1. 分析当前进度，决定下一步行动\n");
        sb.append("2. 如果任务已完成，请输出: ").append(config.getFinishMarker())
                .append(" 并在其后完整、原封不动地转发最后一位执行 Agent 的结果内容，不要做任何额外的总结或改写。\n");
        sb.append("3. 否则，请仅输出下一个要执行的 Agent 名字\n");

        // 简化历史分析规则
        sb.append("\n### 历史分析\n");
        sb.append("你会收到协作历史记录。如果历史信息已足够回答用户问题，可以直接给出最终答案。\n");

        // 简化终止条件
        sb.append("\n### 终止条件\n");
        sb.append("任务完成时输出 ").append(config.getFinishMarker()).append("。\n");
        sb.append("注意：不要过早结束，确保每个必要的专家都有机会参与。");

        return sb.toString();
    }
}