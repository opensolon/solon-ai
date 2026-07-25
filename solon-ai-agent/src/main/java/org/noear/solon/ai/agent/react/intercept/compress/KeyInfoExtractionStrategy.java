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

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;

/**
 * 基于 LLM 的关键信息提取策略实现
 * 相比于全文压缩，该策略更侧重于提取"事实、参数、结论"，过滤掉无用的思考过程。
 *
 * @author noear
 * @since 3.9.4
 */
public class KeyInfoExtractionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(KeyInfoExtractionStrategy.class);

    // 1. 系统指令：定义提取协议和专家身份
    private String systemInstruction = "## 角色定义\n" +
            "你是一个精密的信息审计专家。你的任务是从杂乱的对话历史中\"脱水\"，仅保留高价值的结构化信息。\n\n" +
            "## 提取维度\n" +
            "1. **业务参数**：用户提及的特定 ID、数值、时间、偏好或硬性约束。\n" +
            "2. **确定性事实**：通过工具调用已证实的真实状态或返回的关键结果。\n" +
            "3. **负面路径**：已验证为无效的尝试（防止 Agent 重复错误）。\n" +
            "4. **技术细节**：必须保留所有文件路径、函数名和技术标识，不可省略。\n\n" +
            "## 输出规范\n" +
            "- 必须以简洁的 **Markdown 列表** 形式输出。\n" +
            "- 严禁包含任何推测、解释或修饰性语句。\n" +
            "- 如果没有发现关键信息，请直接回复：(无关键增量)。";

    public KeyInfoExtractionStrategy systemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
        return this;
    }

    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        if (messagesToCompress == null || messagesToCompress.isEmpty()) {
            return null;
        }

        // 过滤初心
        List<ChatMessage> filtered = new ArrayList<>();
        for (ChatMessage m : messagesToCompress) {
            if (!m.hasMetadata(AgentTrace.META_FIRST)) {
                filtered.add(m);
            }
        }
        if (filtered.isEmpty()) return null;

        try {
            String keyInfo = compressWithPTLRetry(chatModel, maxRetries, filtered);

            if (CompressionUtil.isEmptySummary(keyInfo)) {
                return null;
            }

            return CompressionUtil.buildCompressedMessage("--- [已确认的关键信息] ---", keyInfo);

        } catch (Throwable e) {
            log.error("Failed to extract key info", e);
            return null;
        }
    }

    /**
     * 带 PTL 重试的关键信息提取调用。
     * <p>当待压缩历史过大导致 LLM 调用失败时，逐步丢弃最旧的消息缩小范围后重试。
     * 复用 {@link CompressionUtil#isPromptTooLongError} 检测 PTL 异常。
     */
    private String compressWithPTLRetry(ChatModel chatModel, int maxRetries, List<ChatMessage> filtered) throws Throwable {
        final int MAX_PTL_RETRIES = 3;
        List<ChatMessage> currentBatch = filtered;

        for (int ptlAttempt = 0; ptlAttempt <= MAX_PTL_RETRIES; ptlAttempt++) {
            final List<ChatMessage> batch = currentBatch;

            String newHistoryText = CompressionUtil.formatMessages(batch);

            if (Assert.isEmpty(newHistoryText)) return null;

            String userData = "### 待处理历史片段\n" +
                    newHistoryText +
                    "\n\n" +
                    "### 审计要求\n" +
                    "请根据系统指令，提取上述片段中的关键信息。";

            String keyInfo;
            try {
                keyInfo = new RetryTask()
                        .maxRetries(Math.max(1, maxRetries))
                        // PTL 是确定性的输入超限，必须立刻进入外层缩批，不能对同一批次退避重试。
                        .retryIf(e -> !(CompressionUtil.isPromptTooLongError(e)
                                || e instanceof Error
                                || e instanceof InterruptedException
                                || e.getCause() instanceof InterruptedException))
                        .callWithRetry(() -> {
                    ChatResponse resp = chatModel.prompt(userData)
                            .options(o -> {
                                o.agentName(KeyInfoExtractionStrategy.class.getSimpleName());
                                o.systemPrompt(systemInstruction);
                            })
                            .call();

                    if (resp.hasContent()) {
                        return resp.getContent();
                    } else {
                        throw new IllegalStateException("The LLM did not return");
                    }
                        });
            } catch (Throwable e) {
                if (CompressionUtil.isPromptTooLongError(e)) {
                    log.warn("PTL detected via exception, will reduce batch (attempt {}/{})",
                            ptlAttempt + 1, MAX_PTL_RETRIES, e);
                    keyInfo = "prompt is too long";
                } else if (e instanceof InterruptedException) {
                    throw (InterruptedException) e;
                } else if (e.getCause() instanceof InterruptedException) {
                    throw (InterruptedException) e.getCause();
                } else if (e instanceof Error) {
                    throw (Error) e;
                } else if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new IllegalStateException(e); // 非 PTL checked 异常按普通压缩失败上抛
                }
            }

            if (CompressionUtil.isPromptTooLong(keyInfo)) {
                List<ChatMessage> reduced = reduceBatchPreservingSummaries(currentBatch);
                if (reduced == null || reduced.size() >= currentBatch.size()) {
                    log.warn("PTL retry exhausted (attempt {}/{}), batch too small to continue",
                            ptlAttempt + 1, MAX_PTL_RETRIES);
                    return null;
                }

                int currentSize = currentBatch.size();
                currentBatch = reduced;
                log.warn("PTL detected, reduced batch from {} to {} messages for retry (attempt {}/{})",
                        currentSize, reduced.size(), ptlAttempt + 1, MAX_PTL_RETRIES);
                continue;
            }

            return keyInfo;
        }

        return null;
    }

    /**
     * PTL 缩批时保留 META_COMPRESSED 摘要消息，仅裁剪普通历史。
     * <p>旧摘要承载更早历史，PTL 缩批只能淘汰新增历史，不能把滚动摘要一起丢掉。
     */
    private List<ChatMessage> reduceBatchPreservingSummaries(List<ChatMessage> messages) {
        List<ChatMessage> summaries = new ArrayList<>();
        List<ChatMessage> history = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)) {
                summaries.add(message);
            } else {
                history.add(message);
            }
        }

        if (history.size() <= 1) {
            return null;
        }

        int newSize = history.size() / 2;
        if (newSize < 1) {
            return null;
        }

        List<ChatMessage> reduced = new ArrayList<>(summaries.size() + newSize);
        reduced.addAll(summaries);
        reduced.addAll(history.subList(history.size() - newSize, history.size()));
        return reduced;
    }
}
