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
package org.noear.solon.ai.agent.react.intercept;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语义保护型上下文压缩拦截器 (Atomic & Semantic Context Compressor)
 *
 * @author noear
 * @since 3.9.4
 */
@Preview("3.8.2")
public class SummarizationInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SummarizationInterceptor.class);

    // 在类中预加载注册表
    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    // 适配 GPT-4o (o200k_base)，对 DeepSeek 等使用 cl100k_base 的模型有微小偏差（通常 <5%）
    private static final Encoding encoding = registry.getEncodingForModel(ModelType.GPT_4O);
    private static final String META_TOKEN_SIZE = "token_size";

    //轻量级 10，均衡型 13， 代码专家型 16
    private final int maxMessages;
    //轻量级 8000，均衡型 12000，代码专家型 20000+
    private final int maxTokens;
    private final SummarizationStrategy summarizationStrategy;

    public SummarizationInterceptor(int maxMessages, int maxTokens, SummarizationStrategy summarizationStrategy) {
        this.maxMessages = Math.max(10, maxMessages); // 10
        this.maxTokens = Math.max(8000, maxTokens); // 12000
        this.summarizationStrategy = summarizationStrategy;
    }

    public SummarizationInterceptor(int maxMessages, int maxTokens) {
        this(maxMessages, maxTokens, null);
    }

    public SummarizationInterceptor() {
        /**
         * 仅使用 LLMSummarization / HierarchicalSummarization：maxMessages: 10 - 14
         * 仅使用 KeyInfoExtraction：maxMessages: 15 - 20
         * 仅使用 VectorStoreSummarization：maxMessages: 18 - 25
         * 推荐 maxMessages: 10 - 12
         * */

        this(15, 12000, null);
    }

    /**
     * 复制实例，并使用新的限制
     */
    public SummarizationInterceptor copyWith(int maxMessages, int maxTokens) {
        SummarizationInterceptor tmp = new SummarizationInterceptor(
                maxMessages,
                maxTokens,
                this.summarizationStrategy);

        return tmp;
    }

    @Override
    public void onReasonStart(ReActTrace trace, String systemPrompt) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();

        long messageSize = messages.stream()
                .filter(m -> !m.hasMetadata(ReActAgent.META_FIRST))
                .count();

        int currentTokens = estimateTokens(messages, systemPrompt);

        // 预留缓冲，避免频繁重构 (maxMessages + 触发阈值)
        if (messageSize <= maxMessages && currentTokens <= (maxTokens * 0.8)) {
            return;
        }

        // 1. 提取“初心链” (The Original Intent Chain)
        List<ChatMessage> firstList = new ArrayList<>();
        int lastFirstIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg.hasMetadata(ReActAgent.META_FIRST)) {
                firstList.add(msg);
                lastFirstIdx = i;
            }
        }

        // 2. 确定截断起始点 (Sliding Window Start)
        int targetIdx = Math.max(lastFirstIdx + 1, messages.size() - maxMessages);

        if (currentTokens > maxTokens * 0.8) {
            // runningTokens 口径需与 L102 的 currentTokens 保持一致（含 systemPrompt）
            int runningTokens = Assert.isEmpty(systemPrompt) ? 0 : encoding.countTokens(systemPrompt) + 4;
            for (int i = messages.size() - 1; i > lastFirstIdx; i--) {
                ChatMessage msg = messages.get(i);
                // 直接从 metadata 取，因为前面的 estimateTokens(messages) 已经保证了所有消息都有缓存
                Integer cachedSize = msg.getMetadataAs(META_TOKEN_SIZE);
                runningTokens += (cachedSize != null ? cachedSize : 0) + 4;

                if (runningTokens > maxTokens * 0.5) {
                    targetIdx = Math.max(targetIdx, i);
                    break;
                }
            }
        }

        // 3. 增强版原子对齐 (Atomic Alignment)
        while (targetIdx > (lastFirstIdx + 1) && targetIdx < messages.size()) {
            ChatMessage msg = messages.get(targetIdx);
            if (msg instanceof ToolMessage || isObservation(msg)) {
                targetIdx--;
            } else if (msg instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) msg).getToolCalls())) {
                // 停止回溯，这是一个 Action 节点
                break;
            } else {
                break;
            }
        }

        // 4. 语义连贯补齐 (Semantic Completion)
        if (targetIdx > (lastFirstIdx + 1)) {
            ChatMessage prev = messages.get(targetIdx - 1);
            if (prev instanceof AssistantMessage && Assert.isEmpty(((AssistantMessage) prev).getToolCalls())) {
                targetIdx--;
            }
        }

        // 5. 重构 WorkingMemory
        List<ChatMessage> compressed = new ArrayList<>();

        compressed.addAll(firstList);

        if (targetIdx > (lastFirstIdx + 1) && targetIdx <= messages.size()) {
            List<ChatMessage> expired = messages.subList(lastFirstIdx + 1, targetIdx);
            // 过滤掉 expired 中可能存在的旧摘要标记消息，避免“摘要的摘要”产生标题堆叠
            List<ChatMessage> pureHistory = expired.stream()
                    .filter(m -> !m.hasMetadata(ReActAgent.META_SUMMARY))
                    .collect(Collectors.toList());

            if (!pureHistory.isEmpty()) {
                if (summarizationStrategy != null) {
                    ChatMessage summaryMsg = summarizationStrategy.summarize(trace, pureHistory);
                    if (summaryMsg != null) {
                        compressed.add(summaryMsg);
                    }
                } else {
                    // 无摘要策略时，至少保留 expired 区间的最后一条消息，避免上下文完全断裂
                    compressed.add(pureHistory.get(pureHistory.size() - 1));
                }
            }
        }

        compressed.addAll(messages.subList(targetIdx, messages.size()));

        // 6. 更新工作区
        if (compressed.size() < messages.size()) {
            trace.getWorkingMemory().replaceMessages(compressed);

            if (log.isDebugEnabled()) {
                log.debug("ReActAgent [{}] summarized: {} -> {} messages (FirstChain size: {})",
                        trace.getAgentName(), messages.size(), compressed.size(), firstList.size());
            }
        }
    }

    private int estimateTokens(List<ChatMessage> messages, String systemPrompt) {
        int totalTokens = 0;
        for (ChatMessage m : messages) {
            // 尝试从元数据获取缓存值 (META_TOKEN_COUNT 可以定义在 ReActAgent 中)
            Integer cachedCount = m.getMetadataAs(META_TOKEN_SIZE);

            if (cachedCount == null) {
                cachedCount = 0;
                if (m.getContent() != null) {
                    cachedCount += encoding.countTokens(m.getContent());
                }

                // 补算 AssistantMessage 的 toolCalls 序列化开销（content 不包含 toolCalls）
                if (m instanceof AssistantMessage) {
                    AssistantMessage am = (AssistantMessage) m;
                    if (Assert.isNotEmpty(am.getToolCalls())) {
                        for (ToolCall tc : am.getToolCalls()) {
                            cachedCount += encoding.countTokens(tc.getName() + tc.getArgumentsStr());
                            cachedCount += 10; // id + JSON 结构开销
                        }
                    }
                }

                // 将计算结果回填到消息元数据中，下次无需计算
                m.addMetadata(META_TOKEN_SIZE, cachedCount);
            }

            totalTokens += cachedCount + 4; // Overhead
        }

        // systemPrompt 的 token 开销（baseSp、计划看板、Protocol 指令等）
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            totalTokens += encoding.countTokens(systemPrompt) + 4;
        }

        return totalTokens + 3;
    }

    private boolean isObservation(ChatMessage msg) {
        return (msg instanceof ToolMessage) ||
                (msg instanceof UserMessage && msg.getContent() != null && msg.getContent().startsWith("Observation:"));
    }
}