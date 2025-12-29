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

        // 1. 迭代限制：防止死循环
        if (iter.incrementAndGet() > config.getMaxIterations()) {
            context.put("status", "finish");
            context.put("final_answer", "Agent error: Maximum iterations reached.");
            return;
        }

        // 2. 初始化对话：首轮将 prompt 转为 User Message
        if (history.isEmpty()) {
            String prompt = context.getAs("prompt");
            history.add(ChatMessage.ofUser(prompt));
        }

        // 3. 构建全量消息上下文（System + History）
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(config.getSystemPromptTemplate()));
        messages.addAll(history);

        // 4. 发起模型请求
        ChatRequestDesc request = config.getChatModel().prompt(messages);
        request.options(o -> {
            o.temperature((float) config.getTemperature());
            // 使用 optionAdd 增加 stop 序列，兼容性更好
            o.optionAdd("stop", "Observation:");
            if (config.getTools() != null && !config.getTools().isEmpty()) {
                o.toolsAdd(config.getTools());
            }
        });

        ChatResponse response = request.call();
        String content = (response.getContent() == null) ? "" : response.getContent();

        // 5. 更新状态
        history.add(ChatMessage.ofAssistant(content));
        context.put("last_content", content);

        // 6. 决策路由 (LangGraph: should_continue)
        if (content.contains(config.getFinishMarker()) || !content.contains("Action:")) {
            context.put("status", "finish");
            context.put("final_answer", parseFinal(content));
        } else {
            // 模型输出了 Action: 且未结束，引导至工具节点
            context.put("status", "call_tool");
        }
    }

    private String parseFinal(String content) {
        // 优先提取结束标记后的内容
        if (content.contains(config.getFinishMarker())) {
            return content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length()).trim();
        }

        // 容错处理：如果模型直接输出了内容而没带标记，清理一下常见的标识符
        return content.replaceFirst("(?i)^Thought:\\s*", "").trim();
    }
}