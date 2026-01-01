package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 中文提示词提供者（支持全策略协议引导）
 */
public class TeamPromptProviderCn implements TeamPromptProvider {
    private static final TeamPromptProviderCn INSTANCE = new TeamPromptProviderCn();
    public static TeamPromptProviderCn getInstance() { return INSTANCE; }

    @Override
    public String getSystemPrompt(TeamConfig config, Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 角色设定\n");
        sb.append("你是一个团队协作调解器 (Mediator)，负责协调以下 Agent 成员完成任务：\n");
        config.getAgentMap().forEach((name, agent) -> {
            sb.append("- ").append(name).append(": ").append(agent.description()).append("\n");
        });

        sb.append("\n### 当前任务\n").append(prompt.getUserContent()).append("\n");

        sb.append("\n### 协作协议: ").append(config.getStrategy()).append("\n");
        injectStrategyInstruction(sb, config.getStrategy(), config.getFinishMarker());

        sb.append("\n### 输出规范\n");
        sb.append("1. 分析当前进度，决定下一步行动。\n");
        sb.append("2. 如果任务已圆满完成，请务必输出: ").append(config.getFinishMarker()).append("\n");
        sb.append("3. 否则，请输出建议执行的下一个 Agent 名字。");

        return sb.toString();
    }

    private void injectStrategyInstruction(StringBuilder sb, TeamStrategy strategy, String finishMarker) {
        switch (strategy) {
            case HIERARCHICAL:
                sb.append("- 你是最高指挥官。请将任务拆解为步骤，并按顺序指派最合适的 Agent。\n");
                sb.append("- 你负责监督每个成员的产出是否符合预期。");
                break;
            case SWARM:
                sb.append("- 你是动态路由器。Agent 之间是平等的接力关系。\n");
                sb.append("- 请根据上一个 Agent 的处理结果，判断谁最适合处理“接力棒”的下一棒。");
                break;
            case CONTRACT_NET:
                sb.append("- 遵循‘招标-定标’流程。如果还没有征集建议，请先输出 'BIDDING' 启动招标。\n");
                sb.append("- 收到多份 Agent 标书后，对比其方案优劣，选出一人执行。");
                break;
            case BLACKBOARD:
                sb.append("- 历史记录是公共黑板。请检查黑板上哪些信息缺失，哪些逻辑不通。\n");
                sb.append("- 指派能填补黑板空白或修正错误的 Agent。");
                break;
            case MARKET_BASED:
                sb.append("- 每个人都是独立供应商。请考虑效率和专业度。\n");
                sb.append("- 选择能够以最低步数、最高质量解决当前问题的 Agent。");
                break;
        }
    }
}