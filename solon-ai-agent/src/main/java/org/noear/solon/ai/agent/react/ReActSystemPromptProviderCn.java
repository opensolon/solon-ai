package org.noear.solon.ai.agent.react;

/**
 * ReAct 系统提示词提供者（中文版）
 * 强化了对模型逻辑严密性的约束，适用于国内主流大模型。
 *
 * @author noear 2025/12/29 created
 */
public class ReActSystemPromptProviderCn implements ReActSystemPromptProvider {
    @Override
    public String getSystemPrompt(ReActConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个具备自主思考能力的助手，正在使用 ReAct 模式解决问题。\n");
        sb.append("你需要按照以下格式进行回复（请严格遵守）：\n\n");

        sb.append("Thought: 思考你当前需要做什么，以及为什么这样做。\n");
        sb.append("Action: 如果需要调用工具，请输出唯一的 JSON 对象：{\"name\": \"工具名\", \"arguments\": {...}}\n");
        sb.append("   - 示例: {\"name\": \"get_order\", \"arguments\": {\"id\": \"123\"}}\n");
        sb.append("Observation: 这是工具执行后的结果，你将基于此结果进行下一步思考。\n\n");

        sb.append("当你得到最终结论时，请务必使用：").append(config.getFinishMarker())
                .append(" 紧接着输出你的最终答案。\n\n");

        if (!config.getTools().isEmpty()) {
            sb.append("## 可选工具列表（你只能调用以下工具）：\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
        }

        sb.append("\n## 重要注意事项（必须遵守）：\n");
        sb.append("1. **单步执行**：每次回复只能输出一个 Action。严禁一次调用多个工具。\n");
        sb.append("2. **立即停止**：在输出 Action 的 JSON 后，必须立即结束当前回复，等待 Observation。\n");
        sb.append("3. **严禁幻觉**：永远不要自行编写 Observation 的内容，那是系统反馈给你的。\n");
        sb.append("4. **最终回复**：如果无需工具即可回答，或已获得足够信息，请直接使用 ").append(config.getFinishMarker()).append(" 给出结论。");

        return sb.toString();
    }
}