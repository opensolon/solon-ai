package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * @author noear 2025/11/30 created
 */
public class ReActAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private final ChatModel chatModel;
    private final Collection<FunctionTool> toolList;
    private final int maxLoop;

    public ReActAgent(ChatModel chatModel, Collection<FunctionTool> toolList, int maxLoop) {
        this.chatModel = chatModel;
        this.toolList = toolList;
        this.maxLoop = maxLoop;
    }

    @Override
    public String run(String prompt) throws Throwable {
        // 1. 初始化对话历史
        StringBuilder history = new StringBuilder("你是一个遵循 ReAct 模式的智能助手。你的目标是仔细思考（Thought），然后选择一个工具（Action）或给出最终答案（Answer）。\n\n");

        // 2. 注入工具描述
        String toolDescriptions = toolList.stream()
                .map(t -> "Name: " + t.name() + "\nDescription: " + t.description() + "\nInputSchema: " + t.inputSchema() )
                .collect(Collectors.joining("\n\n"));

        history.append("可用的工具:\n").append(toolDescriptions).append("\n\n");

        history.append("用户问题: ").append(prompt).append("\n");

        int maxSteps = maxLoop; // 设置最大循环次数，防止无限循环
        for (int step = 0; step < maxSteps; step++) {
            // 3. 模型推理 (Thought/Action)

            log.info("ReAct-Request: {}", history);
            String response = chatModel.prompt(history.toString()).call().getContent();
            history.append(response).append("\n");
            log.info("ReAct-Response: {}", response);

            // 4. 解析模型输出
            if (response.contains("Answer:")) {
                // 模型决定给出最终答案
                return response.substring(response.indexOf("Answer:") + 7).trim();
            } else if (response.contains("Action:")) {
                // 模型决定使用工具
                String actionText = response.substring(response.indexOf("Action:") + 7).trim();

                // 简化Action解析：假设格式为 ToolName("Input")
                // ⚠️ 实际生产中需要更健壮的正则或JSON解析
                String toolName = actionText.substring(0, actionText.indexOf('(')).trim();
                String toolInput = actionText.substring(actionText.indexOf('(') + 1, actionText.lastIndexOf(')')).replace("\"", "").trim();

                FunctionTool selectedTool = toolList.stream()
                        .filter(t -> t.name().equalsIgnoreCase(toolName))
                        .findFirst()
                        .orElse(null);

                if (selectedTool != null) {
                    // 5. 执行工具 (Observation)
                    String observation = selectedTool.handle(ONode.deserialize(toolInput, Map.class));
                    history.append(observation).append("\n");
                } else {
                    history.append("Observation: Error: 未找到工具 " + toolName + "\n");
                }
            } else {
                // 模型输出格式错误或无法解析，视为失败
                return "Agent 流程中断，模型输出不规范。";
            }
        }

        return "达到最大推理步数，未能得出答案。";
    }
}