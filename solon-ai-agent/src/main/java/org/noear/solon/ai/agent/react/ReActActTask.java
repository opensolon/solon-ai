package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.Assert;
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
public class ReActActTask implements TaskComponent {
    private final ReActConfig config;
    // 正则提取 Action: 后面的 JSON 对象
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(?:```json)?\\s*(\\{.*?\\})\\s*(?:```)?",
            Pattern.DOTALL
    );

    public ReActActTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        ReActState state = context.getAs(ReActState.TAG);
        ChatMessage lastMessage = state.getHistory().get(state.getHistory().size() - 1);

        // --- 1. 处理 Native Tool Calls (遵循 OpenAI/Solon AI 消息对齐协议) ---
        if (lastMessage instanceof AssistantMessage) {
            AssistantMessage lastAssistant = (AssistantMessage) lastMessage;
            if (Assert.isNotEmpty(lastAssistant.getToolCalls())) {
                lastAssistant.getToolCalls().parallelStream().forEach(call -> {
                    Map<String, Object> args = call.arguments();
                    if (args == null) args = Collections.emptyMap();

                    String result = executeTool(call.name(), args);
                    // 注意：ChatMessage 加入 history 需要考虑线程安全或最后统一汇总
                    synchronized (state) {
                        state.addMessage(ChatMessage.ofTool(result, call.name(), call.id()));
                    }
                });
                return; // 处理完 Native 调用直接返回，不再走正则逻辑
            }
        }

        // --- 2. 处理文本 ReAct 模式 (Observation 模拟) ---
        String lastContent = state.getLastResponse();
        if (lastContent == null) return;

        Matcher matcher = ACTION_PATTERN.matcher(lastContent);
        StringBuilder allObservations = new StringBuilder();
        boolean foundAny = false;

        while (matcher.find()) {
            foundAny = true;
            try {
                ONode action = ONode.ofJson(matcher.group(1).trim());
                String toolName = action.get("name").getString();
                ONode argsNode = action.get("arguments");
                Map<String, Object> args = argsNode.isObject() ? argsNode.toBean(Map.class) : Collections.emptyMap();

                String result = executeTool(toolName, args);
                allObservations.append("\nObservation: ").append(result);
            } catch (Exception e) {
                allObservations.append("\nObservation: Error parsing Action JSON: ").append(e.getMessage());
            }
        }

        if (foundAny) {
            // 文本模式通过 User 角色模拟系统反馈
            state.addMessage(ChatMessage.ofUser(allObservations.toString().trim()));
        } else {
            state.addMessage(ChatMessage.ofUser("Observation: No valid Action detected. If you have enough info, please provide Final Answer."));
        }
    }

    private String executeTool(String name, Map<String, Object> args) {
        for (FunctionTool tool : config.getTools()) {
            if (tool.name().equals(name)) {
                try {
                    return tool.handle(args);
                } catch (Throwable e) {
                    return "Error executing tool [" + name + "]: " + e.getMessage();
                }
            }
        }
        return "Tool [" + name + "] not found.";
    }
}