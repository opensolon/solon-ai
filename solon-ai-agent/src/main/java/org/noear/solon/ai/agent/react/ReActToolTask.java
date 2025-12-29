package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReActToolTask implements TaskComponent {
    private final ReActConfig config;
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(\\{.*?\\})", Pattern.DOTALL);

    public ReActToolTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String lastContent = context.getAs("last_content");
        List<ChatMessage> history = context.getAs("conversation_history");

        Matcher matcher = ACTION_PATTERN.matcher(lastContent);
        StringBuilder allObservations = new StringBuilder();
        boolean foundAny = false;

        while (matcher.find()) {
            foundAny = true;
            String json = matcher.group(1).trim();
            try {
                ONode action = ONode.ofJson(json);
                String toolName = action.get("name").getString();
                // 优化：容错处理 arguments
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
                allObservations.append("\nObservation: Error parsing Action JSON: ").append(e.getMessage());
            }
        }

        if (foundAny) {
            history.add(ChatMessage.ofUser(allObservations.toString().trim()));
        } else {
            // 引导模型纠正
            history.add(ChatMessage.ofUser("Observation: No valid Action format found. Please check if you need to call a tool or provide the Final Answer."));
        }
    }
}