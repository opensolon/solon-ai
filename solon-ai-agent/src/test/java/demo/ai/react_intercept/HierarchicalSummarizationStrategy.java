package demo.ai.react_intercept;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 层级摘要策略实现（支持无限续航）
 * 核心逻辑：将“旧摘要”与“新过期的消息”进行递归合并，确保记忆链条永不断裂。
 */
public class HierarchicalSummarizationStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(HierarchicalSummarizationStrategy.class);

    private final ChatModel chatModel;

    private static final String SUMMARY_PREFIX = "--- [全局进度滚动摘要 (层级压缩)] ---";
    private static final String STRATEGY_LASTSUMMARY_KEY = "agent:summary:hierarchical";

    public HierarchicalSummarizationStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        String lastSummary = trace.getExtraAs(STRATEGY_LASTSUMMARY_KEY);
        if (lastSummary == null) {
            lastSummary = "";
        }

        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return buildMessage(lastSummary);
        }

        try {
            // 1. 提取新过期的流水账
            String newHistoryText = messagesToSummarize.stream()
                    .map(m -> String.format("%s: %s", m.getRole().name(), m.getContent()))
                    .collect(Collectors.joining("\n"));

            // 2. 构造层级合并指令
            // 我们要求模型基于旧摘要，融合新信息，输出一个新的全局摘要
            String prompt = String.format(
                    "你是一个记忆管理专家。请将『旧的摘要』与『新增的执行历史』合并，更新为一个精炼的『全局进度摘要』。\n" +
                            "要求：\n" +
                            "1. 保留关键的事实、已确认的数据和最终结论。\n" +
                            "2. 移除重复的思考过程和已失效的尝试。\n" +
                            "3. 保持长度在 500 字以内。\n\n" +
                            "【旧摘要】：\n%s\n\n" +
                            "【新增历史】：\n%s\n\n" +
                            "请输出更新后的全局摘要：",
                    (lastSummary.isEmpty() ? "（暂无）" : lastSummary),
                    newHistoryText
            );

            // 3. 调用模型生成增量摘要
            lastSummary = chatModel.prompt(prompt).call().getContent();

            // 4. 更新内部状态，实现层级滚动
            trace.setExtra(STRATEGY_LASTSUMMARY_KEY, lastSummary);

            if (log.isDebugEnabled()) {
                log.debug("Hierarchical summary updated. Length: {}", lastSummary.length());
            }

            return buildMessage(lastSummary);

        } catch (Exception e) {
            log.error("Hierarchical summarization failed", e);
            // 失败时至少返回旧摘要，保证记忆不丢失
            return buildMessage(lastSummary);
        }
    }

    private ChatMessage buildMessage(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        return ChatMessage.ofSystem(SUMMARY_PREFIX + "\n" + content);
    }
}