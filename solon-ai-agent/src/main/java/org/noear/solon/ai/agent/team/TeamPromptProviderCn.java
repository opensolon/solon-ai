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

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;

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
    public String getSystemPrompt(TeamConfig config, Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个团队协作主管 (Supervisor)，负责协调以下 Agent 成员完成任务：\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- ").append(name).append(": ").append(agent.description()).append("\n");
        });

        sb.append("\n当前任务：").append(prompt.getUserContent()).append("\n");

        sb.append("\n协作协议：").append(config.getStrategy()).append("\n");
        injectStrategyInstruction(sb, config.getStrategy(), config.getFinishMarker());

        sb.append("\n### 输出规范\n");
        sb.append("1. 分析当前进度，决定下一步行动\n");
        sb.append("2. 如果任务已完成，请输出: ").append(config.getFinishMarker()).append(" 并在其后完整保留并引用最后一位执行 Agent 的核心结论，然后进行汇总。\n");
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

    private void injectStrategyInstruction(StringBuilder sb, TeamStrategy strategy, String finishMarker) {
        switch (strategy) {
            case SEQUENTIAL:
                sb.append("- 协作协议：顺序流水线模式。\n");
                sb.append("- 系统将按照成员定义的先后顺序依次指派任务。执行完所有专家后即结束。");
                break;
            case HIERARCHICAL:
                sb.append("- 你是最高指挥官。将任务拆解为步骤，并按顺序指派最合适的 Agent。\n");
                sb.append("- 监督每个成员的产出是否符合预期。");
                break;
            case SWARM:
                sb.append("- 你是动态路由器。Agent 之间是平等的接力关系。\n");
                sb.append("- 根据上一个 Agent 的结果，判断谁最适合处理下一棒。");
                break;
            case CONTRACT_NET:
                sb.append("- 遵循'招标-定标'流程。如果需要多个方案，先输出 'BIDDING' 启动招标。\n");
                sb.append("- 收到标书后，对比方案优劣，选出一人执行。");
                break;
            case BLACKBOARD:
                sb.append("- 历史记录是公共黑板。检查黑板上哪些信息缺失。\n");
                sb.append("- 指派能填补空白或修正错误的 Agent。");
                break;
            case MARKET_BASED:
                sb.append("- 每个人都是独立供应商。考虑效率和专业度。\n");
                sb.append("- 选择能够以最低步数、最高质量解决问题的 Agent。");
                break;
            default:
                sb.append("- 作为团队主管，请根据任务需求和成员能力做出决策。");
                break;
        }
    }
}