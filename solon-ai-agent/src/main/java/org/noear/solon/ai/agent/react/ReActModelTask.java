package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ReActModelTask implements TaskComponent {
    private final ReActConfig config;

    public ReActModelTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        AtomicInteger iter = context.getAs("current_iteration");
        List<ChatMessage> history = context.getAs("conversation_history");

        // 检查迭代次数上限
        if (iter.incrementAndGet() > config.getMaxIterations()) {
            context.put("status", "finish");
            context.put("final_answer", "Reached maximum iterations.");
            return;
        }

        if (history.isEmpty()) {
            history.add(ChatMessage.ofUser(context.<String>getAs("prompt")));
        }

        // 准备请求
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(config.getSystemPromptTemplate()));
        messages.addAll(history);

        ChatRequestDesc request = config.getChatModel().prompt(messages);
        request.options(o -> {
            o.temperature((float) config.getTemperature());
            if (!config.getTools().isEmpty()) o.toolsAdd(config.getTools());
        });

        ChatResponse response = request.call();
        String content = response.getContent() == null ? "" : response.getContent();
        history.add(ChatMessage.ofAssistant(content));
        context.put("last_content", content);

        // 决策逻辑
        if (content.contains(config.getFinishMarker()) || !content.contains("Action:")) {
            context.put("status", "finish");
            context.put("final_answer", extractFinalAnswer(content));
        } else {
            context.put("status", "call_tool");
        }
    }

    private String extractFinalAnswer(String content) {
        if (content.contains(config.getFinishMarker())) {
            return content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length()).trim();
        }
        return content.trim();
    }
}