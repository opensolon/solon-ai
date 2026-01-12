package features.ai.react.generated;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ReActAgent 测试基类
 */
public abstract class ReActAgentTestBase {

    /**
     * 格式化 ReActTrace 的消息为可读字符串
     */
    protected String formatReActHistory(ReActTrace trace) {
        if (trace == null) return "";

        List<ChatMessage> messages = trace.getMessages();
        return messages.stream()
                .map(msg -> {
                    String role = getMessageRole(msg);
                    String content = msg.getContent();
                    return String.format("[%s] %s", role,
                            content != null ? content.substring(0, Math.min(100, content.length())) : "");
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 获取消息角色
     */
    protected String getMessageRole(ChatMessage message) {
        if (message instanceof org.noear.solon.ai.chat.message.UserMessage) {
            return "User";
        } else if (message instanceof org.noear.solon.ai.chat.message.AssistantMessage) {
            return "Assistant";
        } else if (message instanceof org.noear.solon.ai.chat.message.ToolMessage) {
            return "Tool";
        } else {
            return "System";
        }
    }

    /**
     * 检查是否包含 ReAct 模式关键词
     */
    protected boolean containsReActPattern(String text) {
        if (text == null) return false;
        String lowerText = text.toLowerCase();
        return lowerText.contains("thought") ||
                lowerText.contains("action") ||
                lowerText.contains("observation");
    }
}