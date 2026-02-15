/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept.summarize;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
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
 *
 * @author noear
 * @since 3.9.4
 */
public class CompositeSummarizationStrategy implements SummarizationStrategy {
    private final static Logger LOG = LoggerFactory.getLogger(CompositeSummarizationStrategy.class);
    private final List<SummarizationStrategy> strategies = new ArrayList<>();

    // 允许通过构造函数快速组合
    public CompositeSummarizationStrategy(SummarizationStrategy... strategies) {
        for (SummarizationStrategy s : strategies) {
            addStrategy(s);
        }
    }

    public CompositeSummarizationStrategy addStrategy(SummarizationStrategy strategy) {
        if (strategy != null && strategy != this) { // 防环
            strategies.add(strategy);
        }
        return this;
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) return null;

        // 使用初始容量，减少扩容开销
        StringBuilder buf = new StringBuilder(1024);
        for (SummarizationStrategy strategy : strategies) {
            try {
                ChatMessage result = strategy.summarize(trace, messagesToSummarize);
                if (result != null && Assert.isNotEmpty(result.getContent())) {
                    if (buf.length() > 0) buf.append("\n\n");
                    buf.append(result.getContent());
                }
            } catch (Throwable e) { // 捕获 Throwable 确保绝对不崩溃
                LOG.error("Strategy [{}] execution failed", strategy.getClass().getSimpleName(), e);
            }
        }

        return buf.length() == 0 ? null : ChatMessage.ofSystem(buf.toString());
    }
}