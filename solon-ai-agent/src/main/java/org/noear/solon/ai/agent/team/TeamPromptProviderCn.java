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
 * Team 协作模式系统提示词提供者（中文版）
 * * <p>该类负责为团队主管 (Supervisor) 生成系统提示词。核心逻辑在于协调多个 Agent 成员，
 * 根据协作协议（Protocol）决定任务流转或终止，并确保最终输出的完整性。</p>
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

        // 1. 角色定义与团队成员列表
        sb.append("## 角色定义\n");
        sb.append("你是一个团队协作主管 (Supervisor)，负责协调以下 Agent 成员完成任务：\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- **").append(name).append("**: ").append(agent.description()).append("\n");
        });

        // 2. 任务上下文
        sb.append("\n## 当前任务\n").append(trace.getPrompt().getUserContent()).append("\n");

        // 3. 协作协议：由具体策略（如 Sequential, Multi-Agent）注入特定指令
        config.getProtocol().injectSupervisorInstruction(Locale.CHINESE, sb);

        // 4. 严格输出规范：确保 Supervisor 只做决策，不产生多余回复
        sb.append("\n## 输出规范\n");
        sb.append("1. **状态分析**：分析当前执行进度，决定下一步行动。\n");
        sb.append("2. **任务终止**：如果任务已完成，必须输出: ").append(config.getFinishMarker())
                .append(" 并在其后提供最终答案（通常是最后一位执行 Agent 的核心产出或确认信息）。\n"); // 稍微放开，允许合并关键信息
        sb.append("3. **继续执行**：若任务未完成，请**仅输出**下一个要执行的 Agent 名字，不要有额外文本。\n");

        // 5. 历史记录分析逻辑
        sb.append("\n## 历史分析\n");
        sb.append("参考提供的协作历史。如果历史记录中已存在足以回答用户问题的结论，请立即结束任务并输出最终答案。\n");

        // 6. 终止条件约束
        sb.append("\n## 终止条件\n");
        sb.append("- 任务完成信号：").append(config.getFinishMarker()).append("。\n");
        sb.append("- 注意：严禁过早结束，确保所有必要的专家成员均已参与决策。");

        return sb.toString();
    }
}