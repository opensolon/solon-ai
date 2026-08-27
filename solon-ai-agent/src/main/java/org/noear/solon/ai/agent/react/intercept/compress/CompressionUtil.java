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
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩策略公用工具类
 *
 * <p>消除各策略实现间重复的消息格式化与截断逻辑。
 * 所有策略统一通过此工具处理 ToolMessage 内容截断，确保行为一致。
 *
 * @author noear
 * @since 4.0.0
 */
public class CompressionUtil {
    private static final Logger log = LoggerFactory.getLogger(CompressionUtil.class);

    private static final int MAX_PTL_RETRIES = 3;

    /**
     * 默认 ToolMessage 内容截断长度（对齐 claude-code-java 的 TRUNCATION_THRESHOLD = 10,000 字符）
     *
     * @see #formatMessageForCompression(ChatMessage)
     */
    public static final int DEFAULT_MAX_TOOL_RESULT_LENGTH = 10000;

    /**
     * 工具调用内容的截断后缀标记（对齐 claude-code-java 的 "... [truncated, N chars total]" 格式）
     */
    public static final String TRUNCATION_SUFFIX = "... [truncated";

    /**
     * 将消息格式化为供 LLM 压缩或归档用的文本行。
     * <p>使用 {@link #DEFAULT_MAX_TOOL_RESULT_LENGTH}（10000 字符）。
     *
     * @param msg 待格式化的消息
     * @return 格式化后的文本行，不会为 null
     */
    public static String formatMessageForCompression(ChatMessage msg) {
        return formatMessageForCompression(msg, DEFAULT_MAX_TOOL_RESULT_LENGTH);
    }

    /**
     * 将消息格式化为供 LLM 压缩或归档用的文本行。
     * <p>对应 claude-code-java 的消息格式化逻辑：工具调用参数完整保留，
     * 工具结果按阈值截断。与 claude-code-java 的差异是：claude-code-java
     * 直接把原始 Message 对象（含完整 ToolUseBlock.input）发给 LLM，
     * 而本框架将其序列化为文本行格式。
     * <ul>
     *     <li>Assistant(thought + tool_calls) → {@code "[Thought]: <content>\n[Action]: 调用工具 <name>，参数: <args>"}</li>
     *     <li>Assistant(only tool_calls) → {@code "[Action]: 调用工具 <name>，参数: <args>"}</li>
     *     <li>ToolMessage → {@code "[Observation]: 得到结果 <content>"}（超长内容自动截断）</li>
     *     <li>其它消息 → {@code "<role>: <content>"}</li>
     * </ul>
     *
     * @param msg                 待格式化的消息
     * @param maxToolResultLength ToolMessage 内容截断的最大字符数
     * @return 格式化后的文本行，不会为 null
     */
    public static String formatMessageForCompression(ChatMessage msg, int maxToolResultLength) {
        if (msg instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) msg;
            StringBuilder sb = new StringBuilder();
            // 保留思考文本（当 Assistant 同时有 thought 和 tool_calls 时，两者都保留）
            if (Assert.isNotEmpty(am.getContent())) {
                sb.append("[Thought]: ").append(am.getContent());
            }
            // 保留所有工具调用及其完整参数（对应 claude-code-java 保留完整 ToolUseBlock.input）
            if (Assert.isNotEmpty(am.getToolCalls())) {
                for (ToolCall tc : am.getToolCalls()) {
                    if (sb.length() > 0) sb.append('\n');
                    String name = tc.getName() != null ? tc.getName() : "";
                    String args = tc.getArgumentsStr() != null ? tc.getArgumentsStr() : "";
                    sb.append("[Action]: 调用工具 ").append(name);
                    if (!args.isEmpty()) {
                        sb.append("，参数: ").append(args);
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        if (msg instanceof ToolMessage) {
            String content = msg.getContent();
            if (content != null && content.length() > maxToolResultLength) {
                int totalLen = content.length();
                content = content.substring(0, maxToolResultLength) + TRUNCATION_SUFFIX + ", " + totalLen + " chars total]";
            }
            return "[Observation]: 得到结果 " + content;
        }
        return msg.getRole().name() + ": " + msg.getContent();
    }

    /**
     * 将多条消息拼接为压缩用的文本块。
     *
     * @param messages 消息列表
     * @return 拼接后的文本，如全部为空则返回空字符串
     */
    public static String formatMessages(Iterable<ChatMessage> messages) {
        return formatMessages(messages, DEFAULT_MAX_TOOL_RESULT_LENGTH);
    }

    /**
     * 将多条消息拼接为压缩用的文本块。
     *
     * @param messages            消息列表
     * @param maxToolResultLength ToolMessage 截断长度
     * @return 拼接后的文本，如全部为空则返回空字符串
     */
    public static String formatMessages(Iterable<ChatMessage> messages, int maxToolResultLength) {
        StringBuilder buf = new StringBuilder(1024);
        for (ChatMessage m : messages) {
            String line = formatMessageForCompression(m, maxToolResultLength);
            if (line != null) {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(line);
            }
        }
        return buf.toString();
    }

    /**
     * 检查压缩策略的 LLM 返回结果是否标记为"无显著增量"。
     * <p>当前支持以下标记（trim 后完全匹配）：
     * <ul>
     *     <li>{@code (无显著进度)} — LLMCompressionStrategy 使用</li>
     *     <li>{@code (无关键增量)} — KeyInfoExtractionStrategy 使用</li>
     * </ul>
     *
     * @param summary LLM 返回的摘要文本
     * @return true 表示无显著增量，应丢弃该结果
     */
    public static boolean isEmptySummary(String summary) {
        if (Assert.isEmpty(summary)) {
            return true;
        }
        String trimmed = summary.trim();
        return trimmed.equals("(无显著进度)") || trimmed.equals("(无关键增量)");
    }

    /**
     * 检测 LLM 返回结果是否为 Prompt-Too-Long 错误。
     * <p>当压缩策略调用 LLM 的结果文本以 "prompt is too long" 开头时，
     * 说明待压缩的消息段本身过大导致 LLM 调用失败，需要进行范围收窄后重试。
     *
     * @param response LLM 返回的文本
     * @return true 表示需要 PTL 重试
     * @since 4.0.0
     */
    public static boolean isPromptTooLong(String response) {
        return response != null
                && response.length() >= "prompt is too long".length()
                && Character.toLowerCase(response.charAt(0)) == 'p'
                && response.regionMatches(true, 0, "prompt is too long", 0, "prompt is too long".length());
    }

    /**
     * 检测异常是否为 Prompt-Too-Long 错误。
     * <p>PTL 错误在大多数 LLM 提供商中以 API 异常形式抛出，
     * 而非 LLM 返回内容文本。此方法遍历异常链检查是否包含 PTL 相关关键词。
     * <p>支持的关键词（不区分大小写）：
     * <ul>
     *     <li>{@code prompt is too long}</li>
     *     <li>{@code context length}</li>
     *     <li>{@code context_length_exceeded}</li>
     *     <li>{@code maximum context}</li>
     *     <li>{@code maximum input length}</li>
     *     <li>{@code too many tokens}</li>
     *     <li>{@code input_too_long}</li>
     *     <li>{@code request_too_large}</li>
     *     <li>{@code context window}</li>
     * </ul>
     *
     * @param e 待检测的异常
     * @return true 表示是 PTL 错误，可进行范围收窄后重试
     * @since 4.0.0
     */
    public static boolean isPromptTooLongError(Throwable e) {
        if (e == null) return false;
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("prompt is too long")
                        || lower.contains("context length")
                        || lower.contains("context_length_exceeded")
                        || lower.contains("maximum context")
                        || lower.contains("maximum input length")
                        || lower.contains("too many tokens")
                        || lower.contains("input_too_long")
                        || lower.contains("request_too_large")
                        || lower.contains("context window")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断 AssistantMessage 是否包含 ReAct 文本格式的 Action（即内容中包含 "Action:" 标记）。
     * <p>统一 {@link HierarchicalCompressionStrategy} 与 {@link ContextCompressionInterceptor} 中的重复判断逻辑。
     * 注意：此方法仅检查文本内容，不检查 {@code toolCalls}。
     *
     * @param message 待判断的消息
     * @return true 表示消息文本中包含 Action 标记
     * @since 4.0.0
     */
    public static boolean isTextAction(AssistantMessage message) {
        String content = message.getText();
        if (content == null) {
            return false;
        }
        int actionIdx = content.indexOf("Action:");
        return actionIdx >= 0 && actionIdx + "Action:".length() < content.length();
    }

    /**
     * 创建压缩结果消息（带 {@code META_COMPRESSED} 标记）。
     *
     * @param prefix  消息前缀（如 "--- [执行进度总结] ---"）
     * @param content 压缩结果正文
     * @return 标记后的 UserMessage，若 content 为空则返回 null
     */
    public static ChatMessage buildCompressedMessage(String prefix, String content) {
        if (Assert.isEmpty(content)) {
            return null;
        }
        String text;
        if (Assert.isEmpty(prefix)) {
            text = content;
        } else {
            text = prefix + "\n" + content;
        }
        return ChatMessage.ofUser(text)
                .addMetadata(ContextCompressionInterceptor.META_COMPRESSED, 1);
    }

    /**
     * 判断消息是否为工具结果或 Observation。
     * <p>统一各策略实现和拦截器中的重复判断逻辑。
     *
     * @param msg 待判断的消息
     * @return true 表示是工具输出消息
     * @since 4.0.0
     */
    public static boolean isObservation(ChatMessage msg) {
        return msg instanceof ToolMessage || isTextObservation(msg);
    }

    /**
     * 判断 UserMessage 是否为 Observation 文本（以 "Observation:" 开头）。
     *
     * @param msg 待判断的消息
     * @return true 表示是文本格式的 Observation
     * @since 4.0.0
     */
    public static boolean isTextObservation(ChatMessage msg) {
        return msg instanceof org.noear.solon.ai.chat.message.UserMessage
                && msg.getContent() != null
                && msg.getContent().startsWith("Observation:");
    }

    /**
     * PTL 缩批时保留 META_COMPRESSED 摘要消息，仅裁剪普通历史，并执行工具原子对边界对齐。
     *
     * <p>旧摘要承载更早历史，PTL 缩批只能淘汰新增历史，不能把滚动摘要一起丢掉。
     * 裁剪点会通过 {@link #alignToConversationBoundary} 对齐到工具调用组边界，
     * 避免拆散 Assistant(tool_calls) 与其 ToolMessage 结果。
     *
     * @param messages 待缩减的消息列表
     * @return 缩减后的消息列表，若无法进一步缩减则返回 null
     * @since 4.0.0
     */
    public static List<ChatMessage> reduceBatchPreservingSummaries(List<ChatMessage> messages) {
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
     *
     * @param messages 消息列表
     * @param start    裁剪点
     * @return 对齐后的裁剪点
     * @since 4.0.0
     */
    public static int alignToConversationBoundary(List<ChatMessage> messages, int start) {
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

    /**
     * 查找第一个完整的工具调用原子组的结束位置。
     * <p>从消息列表开头开始，找到第一个 Assistant(tool_calls) 及其后续连续
     * ToolMessage/Observation 的结束位置。
     *
     * @param messages 消息列表
     * @return 结束位置；若头部不是工具调用则返回 -1
     * @since 4.0.0
     */
    public static int findFirstAtomicGroupEnd(List<ChatMessage> messages) {
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

    private static final com.knuddels.jtokkit.api.Encoding ENCODING =
            com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
                    .getEncodingForModel(com.knuddels.jtokkit.api.ModelType.GPT_4O);

    /**
     * 获取共享的 Encoding 实例（GPT-4o 编码器）。
     * <p>供拦截器及其他需要直接调用 {@code countTokens} 的类复用，避免重复加载编码注册表。
     *
     * @return 共享的 Encoding 实例
     * @since 4.0.0
     */
    public static com.knuddels.jtokkit.api.Encoding getEncoding() {
        return ENCODING;
    }

    /**
     * 估算文本的 Token 数量（使用 GPT-4o 编码器，对主流模型偏差 <5%）。
     *
     * @param text 待估算的文本
     * @return Token 数量；null 或空文本返回 0
     * @since 4.0.0
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ENCODING.countTokens(text);
    }

    /**
     * PTL 重试循环模板方法。
     *
     * <p>将 {@link org.noear.solon.ai.agent.react.intercept.compress.LLMCompressionStrategy}
     * 和 {@link org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy}
     * 中重复的 compressWithPTLRetry 逻辑抽取为公用方法。差异部分（用户数据模板、
     * 系统指令、代理名称）通过参数传入。
     *
     * <p>当待压缩历史过大导致 LLM 调用失败（返回 prompt is too long）时，
     * 逐步丢弃最旧的消息缩小范围后重试，最多重试 {@value #MAX_PTL_RETRIES} 次。
     *
     * @param chatModel            聊天模型
     * @param maxRetries           普通调用重试次数
     * @param filtered             待压缩的消息列表（已过滤初心）
     * @param systemInstruction    系统指令
     * @param agentName            代理名称（用于 options）
     * @param maxToolResultLength  ToolMessage 截断长度
     * @param userDataPrefix       用户数据前缀（位于格式化历史之前）
     * @param userDataSuffix       用户数据后缀（位于格式化历史之后，含最终指令）
     * @return LLM 返回的汇总文本；PTL 重试耗尽或其他失败时返回 null
     * @throws InterruptedException 线程中断
     * @since 4.0.0
     */
    public static String compressWithPTLRetry(ChatModel chatModel,
                                              int maxRetries,
                                              ReActTrace trace,
                                              List<ChatMessage> filtered,
                                              String systemInstruction,
                                              String agentName,
                                              int maxToolResultLength,
                                              String userDataPrefix,
                                              String userDataSuffix) throws InterruptedException {
        List<ChatMessage> currentBatch = filtered;

        for (int ptlAttempt = 0; ptlAttempt <= MAX_PTL_RETRIES; ptlAttempt++) {
            final List<ChatMessage> batch = currentBatch;

            String newHistoryText = formatMessages(batch, maxToolResultLength);
            if (Assert.isEmpty(newHistoryText)) return null;

            String userData = userDataPrefix + newHistoryText + userDataSuffix;

            String summary;
            try {
                summary = new RetryTask()
                        .maxRetries(Math.max(1, maxRetries))
                        .retryIf(e -> !(isPromptTooLongError(e)
                                || e instanceof Error
                                || e instanceof InterruptedException
                                || e.getCause() instanceof InterruptedException))
                        .callWithRetry(() -> {
                            ChatResponse resp = chatModel.prompt(userData)
                                    .options(o -> {
                                        o.httpCustomizeAdd(trace.getOptions().getModelOptions().httpCustomize());
                                        o.agentName(agentName);
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
                if (isPromptTooLongError(e)) {
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
                    throw new IllegalStateException(e);
                }
            }

            // PTL 检测：若返回 PTL 错误（内容文本或异常转译），缩小范围重试
            if (isPromptTooLong(summary)) {
                int currentSize = currentBatch.size();
                List<ChatMessage> reduced = reduceBatchPreservingSummaries(currentBatch);
                if (reduced == null || reduced.size() >= currentSize) {
                    log.warn("PTL retry exhausted (attempt {}/{}), batch has no safe boundary",
                            ptlAttempt + 1, MAX_PTL_RETRIES);
                    return null;
                }

                currentBatch = reduced;

                log.warn("PTL detected, reduced batch from {} to {} messages (attempt {}/{})",
                        currentSize, reduced.size(), ptlAttempt + 1, MAX_PTL_RETRIES);
                continue;
            }

            return summary;
        }

        return null;
    }
}