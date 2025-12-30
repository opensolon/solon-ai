package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型推理任务
 * 优化点：支持原生 ToolCall + 文本 ReAct 混合模式
 */
public class ReActReasonTask implements TaskComponent {
    private final ReActConfig config;

    public ReActReasonTask(ReActConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        ReActRecord record = context.getAs(ReActRecord.TAG);

        // 1. 迭代限制检查：防止 LLM 陷入无限逻辑循环
        if (record.nextIteration() > config.getMaxIterations()) {
            record.setRoute(ReActRecord.ROUTE_END);
            record.setFinalAnswer("Agent error: Maximum iterations reached.");
            return;
        }

        // 2. 初始化对话：首轮将 prompt 转为 User Message
        if (record.getHistory().isEmpty()) {
            String prompt = record.getPrompt();
            record.addMessage(ChatMessage.ofUser(prompt));
        }

        // 3. 构建全量消息上下文（System + History）
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(config.getSystemPromptTemplate()));
        messages.addAll(record.getHistory());

        // 4. 发起请求并配置 stop 序列（防止模型代写 Observation）
        ChatResponse response = config.getChatModel()
                .prompt(messages)
                .options(o -> {
                    o.autoToolCall(false);
                    o.max_tokens(config.getMaxTokens());
                    o.temperature(config.getTemperature());
                    o.optionAdd("stop", "Observation:"); // 关键：模型遇到此词立即停止，交还控制权
                    if (config.getTools() != null && !config.getTools().isEmpty()) {
                        o.toolsAdd(config.getTools());
                    }
                }).call();

        // --- 处理模型空回复 (防止流程卡死) ---
        if (response.hasChoices() == false || (Assert.isEmpty(response.getContent()) && Assert.isEmpty(response.getMessage().getToolCalls()))) {
            record.addMessage(ChatMessage.ofUser("Your last response was empty. If you need more info, use a tool. Otherwise, provide Final Answer."));
            record.setRoute(ReActRecord.ROUTE_REASON);
            return;
        }

        // --- 核心优化：处理 Native Tool Calls ---
        if (Assert.isNotEmpty(response.getMessage().getToolCalls())) {
            record.addMessage(response.getMessage()); // 存入包含 tool_calls 的消息
            record.setRoute(ReActRecord.ROUTE_ACTION);
            return;
        }

        // --- 兜底：处理文本 ReAct 模式 ---
        String rawContent = response.hasContent() ? response.getContent() : "";
        String clearContent = response.hasContent() ? response.getResultContent() : "";

        // --- 物理截断模型幻觉 (防止模型伪造 Observation) ---
        if (rawContent.contains("Observation:")) {
            rawContent = rawContent.split("Observation:")[0];
        }

        record.addMessage(ChatMessage.ofAssistant(rawContent));
        record.setLastResponse(clearContent);

        if (config.getListener() != null) {
            config.getListener().onThought(context, record, clearContent);
        }

        // 6. 决策路由逻辑。只要有 Action 且没被判定为 Finish，就去执行工具
        if (rawContent.contains(config.getFinishMarker())) {
            record.setRoute(ReActRecord.ROUTE_END);
            record.setFinalAnswer(extractFinalAnswer(clearContent));
        } else if (rawContent.contains("Action:")) {
            record.setRoute(ReActRecord.ROUTE_ACTION);
        } else {
            // 兜底逻辑：如果不含 Action 格式，则视为回答结束
            record.setRoute(ReActRecord.ROUTE_END);
            record.setFinalAnswer(extractFinalAnswer(clearContent));
        }
    }

    /**
     * 解析并清理最终回复内容
     */
    private String extractFinalAnswer(String content) {
        if (content == null) return "";

        if (content.contains(config.getFinishMarker())) {
            content = content.substring(content.indexOf(config.getFinishMarker()) + config.getFinishMarker().length());
        }

        return content.replaceAll("(?s)<think>.*?</think>", "") // 清理思考过程
                .replaceAll("(?m)^(Thought|Action|Observation):\\s*", "") // 清理所有 ReAct 标签
                .trim();
    }
}