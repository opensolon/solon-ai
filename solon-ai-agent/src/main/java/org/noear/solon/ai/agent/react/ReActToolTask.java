package org.noear.solon.ai.agent.react;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReActToolTask implements TaskComponent {
    private final ReActConfig config;
    private static final Pattern ACTION_JSON = Pattern.compile("Action:\\s*(\\{.*?\\})", Pattern.DOTALL);

    public ReActToolTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String lastContent = context.getAs("last_content");
        List<ChatMessage> history = context.getAs("conversation_history");

        Matcher matcher = ACTION_JSON.matcher(lastContent);
        if (matcher.find()) {
            String json = matcher.group(1).trim();
            try {
                ONode action = ONode.ofJson(json);
                String name = action.get("name").getString();
                Map<String, Object> args = action.get("arguments").toBean(Map.class);

                String result = "Tool not found: " + name;
                for (FunctionTool tool : config.getTools()) {
                    if (tool.name().equals(name)) {
                        result = tool.handle(args);
                        break;
                    }
                }
                history.add(ChatMessage.ofUser("Observation: " + result));
            } catch (Exception e) {
                history.add(ChatMessage.ofUser("Observation: Error parsing action - " + e.getMessage()));
            }
        } else {
            history.add(ChatMessage.ofUser("Observation: No valid Action format found."));
        }
    }
}