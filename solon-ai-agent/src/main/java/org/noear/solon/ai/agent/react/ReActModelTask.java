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

/**
 * 模型推理任务
 */
public class ReActModelTask implements TaskComponent {
    private final ReActConfig config;

    public ReActModelTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        AtomicInteger iter = context.getAs("current_iteration");
        List<ChatMessage> history = context.getAs("conversation_history");

        // 1. 迭代限制检查：防止 LLM 陷入无限逻辑循环
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

        // 4. 发起请求并配置 stop 序列（防止模型代写 Observation）
        ChatRequestDesc request = config.getChatModel().prompt(messages);
        request.options(o -> {
            o.temperature(config.getTemperature());
            o.optionAdd("stop", "Observation:"); // 关键：模型遇到此词立即停止，交还控制权
            if (config.getTools() != null && !config.getTools().isEmpty()) {
                o.toolsAdd(config.getTools());
            }
        });

        ChatResponse response = request.call();
        String content = (response.getContent() == null) ? "" : response.getContent();

        // 5. 将回复存入历史与上下文
        history.add(ChatMessage.ofAssistant(content));
        context.put("last_content", content);

        // 6. 决策路由逻辑
        if (content.contains(config.getFinishMarker()) || !content.contains("Action:")) {
            // 包含结束符或不包含 Action 标识时，判定为任务完成
            context.put("status", "finish");
            context.put("final_answer", parseFinal(content));
        } else {
            // 引导至 node_tools 执行 Action
            context.put("status", "call_tool");
        }
    }

    /**
     * 解析并清理最终回复内容
     */
    private String parseFinal(String content) {
        if (content.contains(config.getFinishMarker())) {
            return content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length()).trim();
        }
        return content.replaceFirst("(?i)^Thought:\\s*", "").trim();
    }
}