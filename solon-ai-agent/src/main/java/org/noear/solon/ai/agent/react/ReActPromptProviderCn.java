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
 * ReAct 系统提示词提供者（中文版）- 优化版
 * 修复问题：确保Agent在无工具场景下正确输出[FINISH]标记
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

        // 简化角色描述，减少复杂概念
        sb.append("你是一个解决问题的AI助手。请按照以下格式逐步思考：\n\n");

        sb.append("Thought: 简要解释你的思考过程（1-2句话）\n");

        if (!config.getTools().isEmpty()) {
            sb.append("Action: 如果需要调用工具，请输出唯一的 JSON 对象：{\"name\": \"工具名\", \"arguments\": {...}}\n");
            sb.append("   - 示例: {\"name\": \"get_order\", \"arguments\": {\"id\": \"123\"}}\n");
        } else {
            // 关键修复：明确告诉Agent必须输出[FINISH]
            sb.append("Action: 【重要】没有可用工具。你必须直接输出最终答案。\n");
            sb.append("   格式要求：首先输出 ").append(config.getFinishMarker()).append("，然后是你的答案\n");
            sb.append("   示例：").append(config.getFinishMarker()).append(" 这里是完整的答案内容...\n");
        }

        sb.append("Observation: 系统将提供反馈（不要自己写这个部分）\n\n");

        // 强化最终答案要求
        sb.append("### 最终答案要求（必须遵守）：\n");
        sb.append("1. 当你准备好给出最终答案时，必须以 ").append(config.getFinishMarker()).append(" 开头\n");
        sb.append("2. ").append(config.getFinishMarker()).append(" 后直接跟你的完整答案，不要换行\n");
        sb.append("3. 答案要完整、详细，直接回应用户的问题\n");
        sb.append("4. 不要输出 Thought/Action/Observation 标签\n");
        sb.append("5. 不要输出空的回答\n\n");

        // 针对常见问题的特别指导
        sb.append("### 常见问题指导：\n");
        sb.append("- 如果用户询问旅行规划（如拉萨行程），答案必须包含目的地名称\n");
        sb.append("- 如果用户询问性能测试，提供具体的测试方法和建议\n");
        sb.append("- 如果用户询问技术问题，提供详细的技术解答\n");
        sb.append("- 如果不知道答案，诚实说明，但仍以 ").append(config.getFinishMarker()).append(" 开头\n");

        // 简化工具列表显示
        if (!config.getTools().isEmpty()) {
            sb.append("\n可用工具：\n");
            config.getTools().forEach(t -> sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
        }

        return sb.toString();
    }
}