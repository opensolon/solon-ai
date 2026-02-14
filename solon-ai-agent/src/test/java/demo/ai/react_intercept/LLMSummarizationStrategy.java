package demo.ai.react_intercept;

import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LLM 的语义总结策略实现
 */
public class LLMSummarizationStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(LLMSummarizationStrategy.class);

    private final ChatModel chatModel;
    private final String prompt;

    /**
     * @param chatModel 用于生成摘要的模型（建议使用廉价、快速的模型）
     */
    public LLMSummarizationStrategy(ChatModel chatModel) {
        this(chatModel, "请简要总结以下 AI Agent 的执行历史。说明已尝试的操作、获取的关键信息以及当前的进度。要求：精炼、准确，不超过 300 字。");
    }

    public LLMSummarizationStrategy(ChatModel chatModel, String prompt) {
        this.chatModel = chatModel;
        this.prompt = prompt;
    }

    @Override
    public ChatMessage summarize(List<ChatMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return null;
        }

        try {
            // 1. 将待总结的消息列表转换为易于理解的文本格式
            String historyText = messagesToSummarize.stream()
                    .map(m -> String.format("[%s]: %s", m.getRole().name().toUpperCase(), m.getContent()))
                    .collect(Collectors.joining("\n"));

            // 2. 构建提示词并调用模型
            String requestText = prompt + "\n\n--- 待总结历史 ---\n" + historyText;

            // 使用简易 chat 接口获取结果
            String summary = chatModel.prompt(requestText).call().getContent();

            if (log.isDebugEnabled()) {
                log.debug("LLM Summary generated: {} chars", summary.length());
            }

            // 3. 返回一条包含摘要的系统消息，并带有明显的视觉标记
            return ChatMessage.ofSystem("--- [历史执行摘要 (基于 LLM 自动生成)] ---\n" + summary);

        } catch (Exception e) {
            log.error("Failed to generate LLM summary", e);
            // 如果总结失败，返回 null，拦截器会降级使用 DEFAULT_TRIM_MARKER
            return null;
        }
    }
}