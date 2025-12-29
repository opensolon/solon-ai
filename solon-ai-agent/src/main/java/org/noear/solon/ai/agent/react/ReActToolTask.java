package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具执行任务
 */
public class ReActToolTask implements TaskComponent {
    private final ReActConfig config;
    // 正则提取 Action: 后面的 JSON 对象
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(?:```json)?\\s*(\\{.*?\\})\\s*(?:```)?",
            Pattern.DOTALL
    );

    public ReActToolTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        ReActState state = context.getAs(ReActState.TAG);

        String lastContent = state.getLastContent();
        ChatSession history = state.getConversationHistory();

        Matcher matcher = ACTION_PATTERN.matcher(lastContent);
        StringBuilder allObservations = new StringBuilder();
        boolean foundAny = false;

        // 循环处理，支持单次回复中调用多个工具（并行调用思想）
        while (matcher.find()) {
            foundAny = true;
            String json = matcher.group(1).trim();
            try {
                ONode action = ONode.ofJson(json);
                String toolName = action.get("name").getString();

                // 参数容错处理：确保 arguments 是对象格式
                ONode argsNode = action.get("arguments");
                Map<String, Object> args = argsNode.isObject() ? argsNode.toBean(Map.class) : Collections.emptyMap();

                String result = "Tool not found: " + toolName;
                for (FunctionTool tool : config.getTools()) {
                    if (tool.name().equals(toolName)) {
                        result = tool.handle(args);
                        break;
                    }
                }
                allObservations.append("\nObservation: ").append(result);
            } catch (Exception e) {
                // 异常反馈：让模型知道 JSON 格式错误并尝试在下一轮修正
                allObservations.append("\nObservation: Error parsing Action JSON: ").append(e.getMessage());
            }
        }

        if (foundAny) {
            // 将工具执行结果以 User 身份反馈给对话历史
            history.addMessage(ChatMessage.ofUser(allObservations.toString().trim()));
        } else {
            // 引导提示：如果模型进入此节点却没写 Action，提示其正确格式
            history.addMessage(ChatMessage.ofUser("Observation: No valid Action format found. Please check if you need to call a tool or provide the Final Answer."));
        }
    }
}