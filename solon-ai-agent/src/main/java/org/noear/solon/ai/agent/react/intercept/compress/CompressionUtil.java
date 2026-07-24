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

import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;

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
     * <p>当前支持以下标记（模糊匹配）：
     * <ul>
     *     <li>{@code (无显著进度)} — LLMCompressionStrategy 使用</li>
     *     <li>{@code (无关键增量)} — KeyInfoExtractionStrategy 使用</li>
     * </ul>
     *
     * @param summary LLM 返回的摘要文本
     * @return true 表示无显著增量，应丢弃该结果
     */
    public static boolean isEmptySummary(String summary) {
        return Assert.isEmpty(summary)
                || summary.contains("无显著进度")
                || summary.contains("无关键增量");
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
}
