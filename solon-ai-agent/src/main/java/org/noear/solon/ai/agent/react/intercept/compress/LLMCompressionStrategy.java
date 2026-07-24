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
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的语义压缩策略实现
 * <p>
 * 特性（4.0 新增）：
 * <ul>
 *   <li>使用 {@link CompressionUtil} 统一格式化消息，消除截断逻辑重复</li>
 *   <li>PTL (Prompt-Too-Long) 自动重试：当待压缩历史过大导致 LLM 调用失败时，
 *       逐步收窄范围后重试（对应 claude-code-java 的 PTL 重试机制，最多重试 3 次）</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.4
 */
public class LLMCompressionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(LLMCompressionStrategy.class);

    // PTL 最大重试次数（对应 claude-code-java 的 MAX_PTL_RETRIES = 3）
    private static final int MAX_PTL_RETRIES = 3;

    // 1. 系统指令：定义压缩逻辑和约束
    private String systemInstruction = "## 角色定义\n" +
            "你是一个高效的任务进度分析员。请简要总结 AI Agent 的执行历史片段。\n\n" +
            "## 总结要点\n" +
            "1. **操作回顾**：已尝试的主要操作（工具调用及其关键参数）。\n" +
            "2. **关键发现**：获取到的核心信息或结论。\n" +
            "3. **当前进度**：目前处于任务的哪个阶段，还剩什么未完成。\n" +
            "4. **信息保留**：必须保留所有文件路径、函数名和技术细节，这些是后续执行的关键上下文。\n\n" +
            "## 输出规范\n" +
            "- 要求：精炼、准确，不超过 300 字。\n" +
            "- 严禁包含：无关的客套话或自我介绍。\n" +
            "- 若无可总结内容，请回复：(无显著进度)"; // 统一为英文括号，去掉句号结尾

    public LLMCompressionStrategy systemInstruction(String systemInstruction) {
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
            // PTL 重试循环
            String summary = compressWithPTLRetry(chatModel, Math.max(1, maxRetries), filtered);

            // 模糊匹配“无显著进度”，防大模型胡乱加标点或 Markdown 样式
            if (CompressionUtil.isEmptySummary(summary)) {
                return null;
            }

            // 3. 返回包含标记的消息
            return ChatMessage.ofUser("--- [执行进度总结] ---\n" + summary)
                    .addMetadata(ContextCompressionInterceptor.META_COMPRESSED, 1);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LLM compression interrupted");
            return null;
        } catch (Exception e) {
            log.error("Failed to generate LLM compression", e);
            return null;
        }
    }

    /**
     * 带 PTL 重试的压缩调用。
     * <p>当待压缩历史过大导致 LLM 调用失败（返回 prompt is too long）时，
     * 逐步丢弃最旧的消息缩小范围后重试，最多重试 {@link #MAX_PTL_RETRIES} 次。
     * 对应 claude-code-java 的 CompactService.PTL 重试机制。
     */
    private String compressWithPTLRetry(ChatModel chatModel, int maxRetries,
                                        List<ChatMessage> filtered) throws InterruptedException {
        List<ChatMessage> currentBatch = filtered;

        for (int ptlAttempt = 0; ptlAttempt <= MAX_PTL_RETRIES; ptlAttempt++) {
            final List<ChatMessage> batch = currentBatch;

            // 构建待压缩文本
            String newHistoryText = CompressionUtil.formatMessages(batch, CompressionUtil.DEFAULT_MAX_TOOL_RESULT_LENGTH);
            if (Assert.isEmpty(newHistoryText)) return null;

            String userData = "### 待压缩历史片段\n" +
                    newHistoryText +
                    "\n\n" +
                    "### 任务指令\n" +
                    "请根据系统指令对上述执行过程进行语义总结：";

            String summary;
            try {
                summary = new RetryTask()
                        .maxRetries(Math.max(1, maxRetries))
                        // PTL 是确定性的输入超限，必须立刻进入外层缩批，不能对同一批次退避重试。
                        .retryIf(e -> !(CompressionUtil.isPromptTooLongError(e)
                                || e instanceof Error
                                || e instanceof InterruptedException
                                || e.getCause() instanceof InterruptedException))
                        .callWithRetry(() -> {
                    ChatResponse resp = chatModel.prompt(userData)
                            .options(o -> {
                                o.agentName(LLMCompressionStrategy.class.getSimpleName());
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
                // PTL 可能以 API 异常形式抛出（而非 LLM 返回内容文本）
                if (CompressionUtil.isPromptTooLongError(e)) {
                    log.warn("PTL detected via exception, will reduce batch (attempt {}/{})",
                            ptlAttempt + 1, MAX_PTL_RETRIES, e);
                    // 转入统一 PTL 缩小重试路径
                    summary = "prompt is too long";
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

            // PTL 检测：若返回 PTL 错误（内容文本或异常转译），缩小范围重试
            if (CompressionUtil.isPromptTooLong(summary)) {
                int currentSize = currentBatch.size();
                List<ChatMessage> reduced = reduceBatchPreservingSummaries(currentBatch);
                if (reduced == null || reduced.size() >= currentSize) {
                    // 已经缩减到最小了还是不行，放弃
                    log.warn("PTL retry exhausted (attempt {}/{}), batch has no safe boundary",
                            ptlAttempt + 1, MAX_PTL_RETRIES);
                    return null;
                }

                // 旧摘要承载更早历史，PTL 缩批只能淘汰新增历史，不能把滚动摘要一起丢掉。
                currentBatch = reduced;

                log.warn("PTL detected, reduced batch from {} to {} messages (attempt {}/{})",
                        currentSize, reduced.size(), ptlAttempt + 1, MAX_PTL_RETRIES);
                continue;
            }

            return summary;
        }

        return null;
    }

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

        int targetStart = history.size() / 2;
        int reducedStart = alignToConversationBoundary(history, targetStart);
        if (reducedStart <= 0) {
            // 中点落在首个工具组内时，向前对齐会得到 0；此时应删除完整首组，
            // 而不是误判为没有安全边界。
            reducedStart = findFirstAtomicGroupEnd(history);
        }
        if (reducedStart <= 0 || reducedStart >= history.size()) {
            return null;
        }

        List<ChatMessage> reduced = new ArrayList<>(summaries.size() + history.size() - reducedStart);
        reduced.addAll(summaries);
        reduced.addAll(history.subList(reducedStart, history.size()));
        return reduced;
    }

    /**
     * 将摘要输入的裁剪点对齐到工具调用组边界。
     * 当裁剪点落在连续 ToolMessage/Observation 中时，向前找到其源头 Assistant(tool_calls)。
     * 普通消息不需要额外调整，保留最近半段历史的策略不变。
     */
    private int alignToConversationBoundary(List<ChatMessage> messages, int start) {
        if (start <= 0 || start >= messages.size()) {
            return start;
        }

        ChatMessage atStart = messages.get(start);
        if (!(atStart instanceof ToolMessage) && !isObservation(atStart)) {
            return start;
        }

        for (int i = start - 1; i >= 0; i--) {
            ChatMessage previous = messages.get(i);
            if (previous instanceof AssistantMessage
                    && Assert.isNotEmpty(((AssistantMessage) previous).getToolCalls())) {
                return i;
            }
            if (!(previous instanceof ToolMessage) && !isObservation(previous)) {
                break;
            }
        }

        // 没有可配对的 Assistant 时，保持原边界；主压缩器会在写回前清理孤立工具结果。
        return start;
    }

    private int findFirstAtomicGroupEnd(List<ChatMessage> messages) {
        if (messages.isEmpty() || !(messages.get(0) instanceof AssistantMessage)
                || Assert.isEmpty(((AssistantMessage) messages.get(0)).getToolCalls())) {
            return -1;
        }

        int end = 1;
        while (end < messages.size()
                && (messages.get(end) instanceof ToolMessage || isObservation(messages.get(end)))) {
            end++;
        }
        return end;
    }

    private boolean isObservation(ChatMessage message) {
        return message instanceof ToolMessage
                || (message instanceof org.noear.solon.ai.chat.message.UserMessage
                && message.getContent() != null
                && message.getContent().startsWith("Observation:"));
    }
}
