/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react;

import org.noear.solon.lang.Preview;

/**
 * ReAct 系统提示词提供者（中文版）
 * 强化了对推理轨迹 (Trace) 的感知，并严格约束 JSON 输出。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ReActPromptProviderCn implements ReActPromptProvider {
    private static final ReActPromptProvider instance = new ReActPromptProviderCn();

    public static ReActPromptProvider getInstance() {
        return instance;
    }

    @Override
    public String getSystemPrompt(ReActConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个具备自主思考能力的助手，正在通过维护一段**推理轨迹 (Trace)** 来解决问题。\n");
        sb.append("你需要按照以下 ReAct 规范逐步推进轨迹（请严格遵守）：\n\n");

        sb.append("Thought: 思考你当前在轨迹中所处的位置，以及下一步需要做什么，并解释原因。\n");

        if (!config.getTools().isEmpty()) {
            sb.append("Action: 如果需要调用工具，请输出唯一的 JSON 对象：{\"name\": \"工具名\", \"arguments\": {...}}\n");
            sb.append("   - 示例: {\"name\": \"get_order\", \"arguments\": {\"id\": \"123\"}}\n");
        } else {
            sb.append("Action: 【重要提示】当前没有可用的工具，请不要尝试调用任何工具。\n");
            sb.append("   如果用户要求使用工具，请直接使用 ").append(config.getFinishMarker())
                    .append(" 告知用户当前无法提供该服务。\n");
        }

        sb.append("Observation: 这是工具执行后的系统反馈。你将基于此轨迹点进行下一步思考。\n\n");

        sb.append("当你得到最终结论时，请务必使用：").append(config.getFinishMarker())
                .append(" 紧接着输出你的最终答案。\n\n");

        if (!config.getTools().isEmpty()) {
            sb.append("## 可选工具列表（你只能调用以下工具）：\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
            sb.append("\n## 重要注意事项（必须遵守）：\n");
            sb.append("1. **轨迹连贯性**：每一次 Thought 必须基于之前的轨迹记录（Trace）进行严密的逻辑推理。\n");
            sb.append("2. **单步执行**：每次回复只能输出一个 Action。严禁一次调用多个工具。\n");
            sb.append("3. **立即停止**：在输出 Action 的 JSON 后，必须立即结束当前回复，等待系统给出 Observation。\n");
            sb.append("4. **严禁幻觉**：永远不要自行编写 Observation 的内容，那是系统反馈给你的。\n");
            sb.append("5. **最终回复**：如果无需工具即可回答，或已获得足够轨迹信息，请直接使用 ").append(config.getFinishMarker()).append(" 给出结论。");
        } else {
            sb.append("## 重要提示：\n");
            sb.append("1. **当前没有可用工具**：请不要尝试调用任何工具。\n");
            sb.append("2. **直接回答问题**：基于你的知识和推理能力直接回答用户的问题。\n");
            sb.append("3. **严禁幻觉**：如果不知道答案，请诚实地告知用户。\n");
            sb.append("4. **使用结束标记**：当完成回答时，请使用 '").append(config.getFinishMarker()).append("' 开始你的最终答案。");
        }

        return sb.toString();
    }
}