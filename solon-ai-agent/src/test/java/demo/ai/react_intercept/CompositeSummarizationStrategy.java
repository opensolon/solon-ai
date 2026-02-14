package demo.ai.react_intercept;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 多策略级联总结策略 (Composite Summarization Strategy)
 * 核心逻辑：按顺序执行多个子策略，并决定如何合并它们的输出。
 *
 * <pre>{@code
 * // 1. 构建级联策略
 * SummarizationStrategy composite = new CompositeSummarizationStrategy()
 *     .addStrategy(new VectorStoreSummarizationStrategy(myRepo)) // 先存档
 *     .addStrategy(new KeyInfoExtractionStrategy(chatModel))     // 再提纯
 *     .addStrategy(new HierarchicalSummarizationStrategy(chatModel)); // 后压缩
 *
 * // 2. 注入拦截器
 * SummarizationInterceptor interceptor = new SummarizationInterceptor(12, composite);
 * }</pre>
 */
public class CompositeSummarizationStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(CompositeSummarizationStrategy.class);

    private final List<SummarizationStrategy> strategies = new ArrayList<>();

    /**
     * 添加子策略（执行顺序取决于添加顺序）
     */
    public CompositeSummarizationStrategy addStrategy(SummarizationStrategy strategy) {
        if (strategy != null) {
            strategies.add(strategy);
        }
        return this;
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return null;
        }

        StringBuilder compositeContent = new StringBuilder();

        for (SummarizationStrategy strategy : strategies) {
            try {
                // 依次执行子策略
                ChatMessage result = strategy.summarize(trace, messagesToSummarize);

                if (result != null && result.getContent() != null) {
                    if (compositeContent.length() > 0) {
                        compositeContent.append("\n\n");
                    }
                    // 将各策略的产出合并到一个 SystemMessage 中
                    compositeContent.append(result.getContent());
                }
            } catch (Exception e) {
                log.error("Sub-strategy execution failed: " + strategy.getClass().getSimpleName(), e);
                // 单个子策略失败，继续执行后续策略
            }
        }

        if (compositeContent.length() == 0) {
            return null;
        }

        // 返回一个聚合了所有策略结果的消息
        return ChatMessage.ofSystem(compositeContent.toString());
    }
}