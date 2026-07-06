/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept.compress;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 多策略级联压缩策略 (Composite Compression Strategy)
 * 核心逻辑：按顺序执行所有子策略，将各策略输出合并为一条摘要消息。
 *
 * <p>适合需要多层处理的场景，例如：先用 VectorStore 存档原始内容，
 * 再用 KeyInfoExtraction 提取关键信息，最后用 HierarchicalCompression 生成滚动摘要。
 * 每个子策略独立执行，互不干扰，结果按顺序以 Markdown 分割线拼接。
 *
 * <pre>{@code
 * // 1. 构建级联策略
 * CompressionStrategy composite = new CompositeCompressionStrategy()
 *     .addStrategy(new VectorStoreCompressionStrategy(myRepo)) // 先存档
 *     .addStrategy(new KeyInfoExtractionStrategy(chatModel))     // 再提纯
 *     .addStrategy(new HierarchicalCompressionStrategy(chatModel)); // 后压缩
 *
 * // 2. 注入拦截器
 * ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor(12, 15000, composite);
 * }</pre>
 *
 * @author noear
 * @since 3.9.4
 */
public class CompositeCompressionStrategy implements CompressionStrategy {
    private final static Logger LOG = LoggerFactory.getLogger(CompositeCompressionStrategy.class);
    private final List<CompressionStrategy> strategies = new ArrayList<>();

    // 允许通过构造函数快速组合
    public CompositeCompressionStrategy(CompressionStrategy... strategies) {
        for (CompressionStrategy s : strategies) {
            addStrategy(s);
        }
    }

    public CompositeCompressionStrategy addStrategy(CompressionStrategy strategy) {
        if (strategy != null && strategy != this) { // 防环
            strategies.add(strategy);
        }
        return this;
    }

    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        if (messagesToCompress == null || messagesToCompress.isEmpty()) return null;

        StringBuilder buf = new StringBuilder(1024);
        for (CompressionStrategy strategy : strategies) {
            try {
                ChatMessage result = strategy.compress(chatModel, maxRetries, trace, messagesToCompress);
                if (result != null && Assert.isNotEmpty(result.getContent())) {
                    if (buf.length() > 0) {
                        buf.append("\n\n---\n\n"); // 使用明显的 Markdown 分割线
                    }

                    buf.append(result.getContent());
                }
            } catch (Throwable e) { // 捕获 Throwable 确保绝对不崩溃
                LOG.error("Strategy [{}] execution failed", strategy.getClass().getSimpleName(), e);
            }
        }

        if (buf.length() == 0) {
            return null;
        }

        return ChatMessage.ofUser(buf.toString())
                .addMetadata(ContextCompressionInterceptor.META_COMPRESSED, 1);
    }
}
